package cask.internal

import language.experimental.macros
import scala.annotation.StaticAnnotation
import scala.reflect.macros.blackbox.Context

/**
  * More or less a minimal version of Autowire's Server that lets you generate
  * a set of "routes" from the methods defined in an object, and call them
  * using passing in name/args/kwargs via Java reflection, without having to
  * generate/compile code or use Scala reflection. This saves us spinning up
  * the Scala compiler and greatly reduces the startup time of cached scripts.
  */
object Router{
  class doc(s: String) extends StaticAnnotation

  /**
    * Models what is known by the router about a single argument: that it has
    * a [[name]], a human-readable [[typeString]] describing what the type is
    * (just for logging and reading, not a replacement for a `TypeTag`) and
    * possible a function that can compute its default value
    */
  case class ArgSig[I, -T, +V, -C](name: String,
                                   typeString: String,
                                   doc: Option[String],
                                   default: Option[T => V])
                                  (implicit val reads: ArgReader[I, V, C])

  trait ArgReader[I, +T, -C]{
    def arity: Int
    def read(ctx: C, label: String, input: I): T
  }

  def stripDashes(s: String) = {
    if (s.startsWith("--")) s.drop(2)
    else if (s.startsWith("-")) s.drop(1)
    else s
  }

  /**
    * What is known about a single endpoint for our routes. It has a [[name]],
    * [[argSignatures]] for each argument, and a macro-generated [[invoke0]]
    * that performs all the necessary argument parsing and de-serialization.
    *
    * Realistically, you will probably spend most of your time calling [[invoke]]
    * instead, which provides a nicer API to call it that mimmicks the API of
    * calling a Scala method.
    */
  case class EntryPoint[T, C](name: String,
                              argSignatures: Seq[Seq[ArgSig[_, T, _, C]]],
                              doc: Option[String],
                              invoke0: (T, C, Seq[Map[String, Any]]) => Result[Any]){
    def invoke(target: T,
               ctx: C,
               paramLists: Seq[Map[String, Any]]): Result[Any] = {

      val unknown = paramLists.head.keySet -- argSignatures.head.map(_.name).toSet
      val missing = argSignatures.head.filter(as =>
        as.reads.arity != 0 && !paramLists.head.contains(as.name) && as.default.isEmpty
      )

      if (missing.nonEmpty || unknown.nonEmpty) Result.Error.MismatchedArguments(missing, unknown.toSeq)
      else {
        try invoke0(target, ctx, paramLists)
        catch{case e: Throwable => Result.Error.Exception(e)}
      }
    }
  }

  def tryEither[T](t: => T, error: Throwable => Result.ParamError) = {
    try Right(t)
    catch{ case e: Throwable => Left(error(e))}
  }

  /**
    * Represents what comes out of an attempt to invoke an [[EntryPoint]].
    * Could succeed with a value, but could fail in many different ways.
    */
  sealed trait Result[+T]
  object Result{

    /**
      * Invoking the [[EntryPoint]] was totally successful, and returned a
      * result
      */
    case class Success[T](value: T) extends Result[T]

    /**
      * Invoking the [[EntryPoint]] was not successful
      */
    sealed trait Error extends Result[Nothing]


    object Error{


      /**
        * Invoking the [[EntryPoint]] failed with an exception while executing
        * code within it.
        */
      case class Exception(t: Throwable) extends Error

      /**
        * Invoking the [[EntryPoint]] failed because the arguments provided
        * did not line up with the arguments expected
        */
      case class MismatchedArguments(missing: Seq[ArgSig[_, _, _, _]],
                                     unknown: Seq[String]) extends Error
      /**
        * Invoking the [[EntryPoint]] failed because there were problems
        * deserializing/parsing individual arguments
        */
      case class InvalidArguments(values: Seq[ParamError]) extends Error
    }

    sealed trait ParamError
    object ParamError{
      /**
        * Something went wrong trying to de-serialize the input parameter;
        * the thrown exception is stored in [[ex]]
        */
      case class Invalid(arg: ArgSig[_, _, _, _], value: String, ex: Throwable) extends ParamError
      /**
        * Something went wrong trying to evaluate the default value
        * for this input parameter
        */
      case class DefaultFailed(arg: ArgSig[_, _, _, _], ex: Throwable) extends ParamError
    }
  }

  type FailMaybe = Either[Seq[Result.ParamError], Any]

  def validate(args: Seq[FailMaybe]): Result[Seq[Any]] = {
    val lefts = args.collect{case Left(x) => x}.flatten

    if (lefts.nonEmpty) Result.Error.InvalidArguments(lefts)
    else {
      val rights = args.collect{case Right(x) => x}
      Result.Success(rights)
    }
  }

  def makeReadCall[I, C](dict: Map[String, I],
                         ctx: C,
                         default: => Option[Any],
                         arg: ArgSig[I, _, _, C]) = {
    arg.reads.arity match{
      case 0 =>
        tryEither(
          arg.reads.read(ctx, arg.name, null.asInstanceOf[I]), Result.ParamError.DefaultFailed(arg, _)).left.map(Seq(_))
      case 1 =>
        dict.get(arg.name) match{
          case None =>
            tryEither(default.get, Result.ParamError.DefaultFailed(arg, _)).left.map(Seq(_))

          case Some(x) =>
            tryEither(arg.reads.read(ctx, arg.name, x), Result.ParamError.Invalid(arg, x.toString, _)).left.map(Seq(_))
        }
    }
  }
}


class Router[C <: Context](val c: C) {
  import c.universe._
  def getValsOrMeths(curCls: Type): Iterable[MethodSymbol] = {
    def isAMemberOfAnyRef(member: Symbol) = {
      // AnyRef is an alias symbol, we go to the real "owner" of these methods
      val anyRefSym = c.mirror.universe.definitions.ObjectClass
      member.owner == anyRefSym
    }
    val extractableMembers = for {
      member <- curCls.members.toList.reverse
      if !isAMemberOfAnyRef(member)
      if !member.isSynthetic
      if member.isPublic
      if member.isTerm
      memTerm = member.asTerm
      if memTerm.isMethod
      if !memTerm.isModule
    } yield memTerm.asMethod

    extractableMembers flatMap { case memTerm =>
      if (memTerm.isSetter || memTerm.isConstructor || memTerm.isGetter) Nil
      else Seq(memTerm)

    }
  }

  def extractMethod(meth: MethodSymbol,
                    curCls: c.universe.Type,
                    wrapOutput: c.Tree => c.Tree,
                    ctx: c.Type,
                    argReaders: Seq[c.Tree],
                    annotDeserializeTypes: Seq[c.Tree]): c.universe.Tree = {
    val baseArgSym = TermName(c.freshName())

    def getDocAnnotation(annotations: List[Annotation]) = {
      val (docTrees, remaining) = annotations.partition(_.tpe =:= typeOf[Router.doc])
      val docValues = for {
        doc <- docTrees
        if doc.scalaArgs.head.isInstanceOf[Literal]
        l = doc.scalaArgs.head.asInstanceOf[Literal]
        if l.value.value.isInstanceOf[String]
      } yield l.value.value.asInstanceOf[String]
      (remaining, docValues.headOption)
    }
    val (_, methodDoc) = getDocAnnotation(meth.annotations)
    val argListSymbol = q"${c.fresh[TermName]("argsList")}"
    val argData = for(argListIndex <- 0 until meth.paramLists.length) yield{
      val annotDeserializeType = annotDeserializeTypes(argListIndex)
      val argReader = argReaders(argListIndex)
      val flattenedArgLists = meth.paramss(argListIndex)
      def hasDefault(i: Int) = {
        val defaultName = s"${meth.name}$$default$$${i + 1}"
        if (curCls.members.exists(_.name.toString == defaultName)) Some(defaultName)
        else None
      }



      val defaults = for ((arg, i) <- flattenedArgLists.zipWithIndex) yield {
        val arg = TermName(c.freshName())
        hasDefault(i).map(defaultName => q"($arg: $curCls) => $arg.${newTermName(defaultName)}")
      }


      def unwrapVarargType(arg: Symbol) = {
        val vararg = arg.typeSignature.typeSymbol == definitions.RepeatedParamClass
        val unwrappedType =
          if (!vararg) arg.typeSignature
          else arg.typeSignature.asInstanceOf[TypeRef].args(0)

        (vararg, unwrappedType)
      }



      val readArgSigs = for (
        ((arg, defaultOpt), i) <- flattenedArgLists.zip(defaults).zipWithIndex
      ) yield {

        val (vararg, varargUnwrappedType) = unwrapVarargType(arg)

        val default =
          if (vararg) q"scala.Some(scala.Nil)"
          else defaultOpt match {
            case Some(defaultExpr) => q"scala.Some($defaultExpr($baseArgSym))"
            case None => q"scala.None"
          }

        val (docUnwrappedType, docOpt) = varargUnwrappedType match {
          case t: AnnotatedType =>
            import compat._
            val (remaining, docValue) = getDocAnnotation(t.annotations)
            if (remaining.isEmpty) (t.underlying, docValue)
            else (c.universe.AnnotatedType(remaining, t.underlying), docValue)

          case t => (t, None)
        }

        val docTree = docOpt match {
          case None => q"scala.None"
          case Some(s) => q"scala.Some($s)"
        }

        val argSig =
          q"""
          cask.internal.Router.ArgSig[$annotDeserializeType, $curCls, $docUnwrappedType, $ctx](
            ${arg.name.toString},
            ${docUnwrappedType.toString},
            $docTree,
            $defaultOpt
          )($argReader[$docUnwrappedType])
        """

        val reader =
          if (vararg) c.abort(meth.pos, "Varargs are not supported in cask routes")
          else
            q"""
            cask.internal.Router.makeReadCall(
              $argListSymbol($argListIndex),
              ctx,
              $default,
              $argSig.asInstanceOf[cask.internal.Router.ArgSig[Any, _, _, cask.model.ParamContext]]
            )
          """
        c.internal.setPos(reader, meth.pos)
        (reader, argSig)
      }

      val (readArgs, argSigs) = readArgSigs.unzip
      val (argNames, argNameCasts) = flattenedArgLists.map { arg =>
        val (vararg, unwrappedType) = unwrapVarargType(arg)
        (
          pq"${arg.name.toTermName}",
          if (!vararg) q"${arg.name.toTermName}.asInstanceOf[$unwrappedType]"
          else q"${arg.name.toTermName}.asInstanceOf[Seq[$unwrappedType]]: _*"

        )
      }.unzip

      (argNameCasts, argSigs, argNames, readArgs)
    }

    val argNameCasts = argData.map(_._1)
    val argSigs = argData.map(_._2)
    val argNames = argData.map(_._3)
    val readArgs = argData.map(_._4)
    var methodCall: c.Tree = q"$baseArgSym.${meth.name.toTermName}"
    for(argNameCast <- argNameCasts) methodCall = q"$methodCall(..$argNameCast)"

    val res = q"""
    cask.internal.Router.EntryPoint[$curCls, $ctx](
      ${meth.name.toString},
      ${argSigs.toList},
      ${methodDoc match{
      case None => q"scala.None"
      case Some(s) => q"scala.Some($s)"
    }},
      ($baseArgSym: $curCls, ctx: $ctx, $argListSymbol: Seq[Map[String, Any]]) =>
        cask.internal.Router.validate(Seq(..${readArgs.flatten.toList})) match{
          case cask.internal.Router.Result.Success(Seq(..${argNames.flatten.toList})) =>

            ${wrapOutput(methodCall)}

          case x: cask.internal.Router.Result.Error => x
        }
    ).asInstanceOf[cask.internal.Router.EntryPoint[$curCls, $ctx]]
    """

    c.internal.transform(res){(t, a) =>
      c.internal.setPos(t, meth.pos)
      a.default(t)
    }

    res
  }

}
