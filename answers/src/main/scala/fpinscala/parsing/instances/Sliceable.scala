package fpinscala
package parsing

import scala.util.matching.Regex

/*
This implementation is a bit trickier than the one in `Reference.scala`.
The main changes are introducing `ParseState` as a wrapper for a `Location`
and an `isSliced` flag, and adding an additional `Slice` constructor to `Result`.
If the `isSliced` flag is set, parsers avoid building a meaningful
result--see in particular the overridden implementations for `map`,
`map2`, and `many`.
*/
object Sliceable extends Parsers[Sliceable.Parser]:

  /* A parser is a kind of state action that can fail.
   * This type is slightly fancier than the one discussed in the chapter,
   * to support efficient slicing. If the parser is surrounded by
   * a `slice` combinator, the `isSliced` field of `ParseState` will
   * be `true`, and we return a `Slice` output.
   */
  opaque type Parser[+A] = ParseState => Result[A]

  /** `isSliced` indicates if the current parser is surround by a
    * `slice` combinator. This lets us avoid building up values that
    * will end up getting thrown away.
    *
    * There are several convenience functions on `ParseState` to make
    * implementing some of the combinators easier.
    */
  case class ParseState(loc: Location, isSliced: Boolean):
    // some convenience functions
    def advanceBy(numChars: Int): ParseState =
      copy(loc = loc.advanceBy(numChars))
    def input: String = loc.input.substring(loc.offset)
    def unslice = copy(isSliced = false)
    def reslice(s: ParseState) = copy(isSliced = s.isSliced)
    def slice(n: Int) = loc.input.substring(loc.offset, loc.offset + n)

  /** The result of a parse--a `Parser[A]` returns a `Result[A]`.
    *
    * There are three cases:
    *   - Success(a,n): a is the value, n is # of consumed characters
    *   - Slice(n): a successful slice; n is the # of consumed characters
    *   - Failure(n,isCommitted): a failing parse
    *
    * As usual, we define some helper functions on `Result`.
    * Defining functions on `Result` gives us better type
    * information--there are cases (see `map` and `map2` below) where
    * Scala will not appropriately refine type information when
    * pattern matching on `Result`.
    */
  sealed trait Result[+A]:
    def extract(input: String): Either[ParseError,A]
    def slice: Result[String]
    /* Used by `attempt`. */
    def uncommit: Result[A] = this match
      case Failure(e,true) => Failure(e, false)
      case _ => this

    /* Used by `flatMap` */
    def addCommit(isCommitted: Boolean): Result[A] = this match
      case Failure(e,c) => Failure(e, c || isCommitted)
      case _ => this

    /* Used by `scope`, `label`. */
    def mapError(f: ParseError => ParseError): Result[A] = this match
      case Failure(e,c) => Failure(f(e), c)
      case _ => this

    def advanceSuccess(n: Int): Result[A]

  case class Slice(length: Int) extends Result[String]:
    def extract(s: String) = Right(s.substring(0,length))
    def slice = this
    def advanceSuccess(n: Int) = Slice(length+n)

  case class Success[+A](get: A, length: Int) extends Result[A]:
    def extract(s: String) = Right(get)
    def slice = Slice(length)
    def advanceSuccess(n: Int) = Success(get, length+n)

  case class Failure(get: ParseError, isCommitted: Boolean) extends Result[Nothing]:
    def extract(s: String) = Left(get)
    def slice = this
    def advanceSuccess(n: Int) = this

  /** Returns -1 if s.startsWith(s2), otherwise returns the
    * first index where the two strings differed. If s2 is
    * longer than s1, returns s.length. */
  def firstNonmatchingIndex(s: String, s2: String, offset: Int): Int =
    var i = 0
    while (i + offset < s.length && i < s2.length)
      if s.charAt(i + offset) != s2.charAt(i) then return i
      i += 1
    if s.length - offset >= s2.length then -1
    else s.length - offset

      // consume no characters and succeed with the given value
  def succeed[A](a: A): Parser[A] =
    s => Success(a, 0)

  extension [A](p: Parser[A])
    def run(s: String): Either[ParseError, A] =
      p(ParseState(Location(s), false)).extract(s)

    def or(p2: => Parser[A]): Parser[A] =
      s => p(s) match
        case Failure(e,false) => p2(s)
        case r => r // committed failure or success skips running `p2`

    /*
    * `Result` is an example of a Generalized Algebraic Data Type (GADT),
    * which means that not all the data constructors of `Result` have
    * the same type. In particular, `Slice` _refines_ the `A` type
    * parameter to be `String`. If we pattern match on a `Result`
    * and obtain a `Slice`, we expect to be able to assume that `A` was
    * in fact `String` and use this type information elsewhere.
    */

    /* This implementation is rather delicate. Since we need an `A`
    * to generate the second parser, we need to run the first parser
    * 'unsliced', even if the `flatMap` is wrapped in a `slice` call.
    * Once we have the `A` and have generated the second parser to
    * run, we can 'reslice' the second parser.
    *
    * Note that this implementation is less efficient than it could
    * be in the case where the choice of the second parser does not
    * depend on the first (as in `map2`). In that case, we could
    * continue to run the first parser sliced.
    */
    def flatMap[B](f: A => Parser[B]): Parser[B] =
      s => p(s.unslice) match
        case Success(a, n) =>
          f(a)(s.advanceBy(n).reslice(s))
            .addCommit(n != 0)
            .advanceSuccess(n)
        case Slice(n) =>
          f(s.slice(n))(s.advanceBy(n).reslice(s)).advanceSuccess(n)
        case f @ Failure(_, _) => f

    def attempt: Parser[A] = s => p(s).uncommit

    def slice: Parser[String] =
      s => p(s.copy(isSliced = true)).slice

    override def product[B](p2: => Parser[B]): Parser[(A, B)] =
      p.map2(p2)((_, _))

    /* Pattern matching on Slice refines the type `A` to `String`,
    * and allow us to call `f(s.slice(n))`, since `f` accepts an
    * `A` which is known to be `String`.
    */
    override def map[B](f: A => B): Parser[B] =
      s => p(s) match
        case Success(a, n) => Success(f(a), n)
        case Slice(n) => Success(f(s.slice(n)), n)
        case f@Failure(_,_) => f

    override def map2[B, C](p2: => Parser[B])(f: (A, B) => C): Parser[C] =
      s => p(s) match
        case Success(a,n) => 
          val s2 = s.advanceBy(n)
          p2(s2) match
            case Success(b,m) => Success(f(a, b), n + m)
            case Slice(m) => Success(f(a,s2.slice(m)), n + m)
            case f@Failure(_,_) => f
        case Slice(n) => 
          val s2 = s.advanceBy(n)
          p2(s2) match
            case Success(b,m) => Success(f(s.slice(n), b), n + m)
            case Slice(m) =>
              if s.isSliced then Slice(n + m).asInstanceOf[Result[C]]
              else Success(f(s.slice(n), s2.slice(m)), n + m)
            case f@Failure(_,_) => f
        case f@Failure(_,_) => f

    /* We provide an overridden version of `many` that accumulates
    * the list of results using a monolithic loop. This avoids
    * stack overflow errors.
    */
    override def many: Parser[List[A]] =
      s =>
        var nConsumed: Int = 0
        if s.isSliced then
          def go(p: Parser[String], offset: Int): Result[String] =
            p(s.advanceBy(offset)) match
              case f@Failure(e,true) => f
              case Failure(e,_) => Slice(offset)
              case Slice(n) => go(p, offset+n)
              case Success(_,_) => sys.error("sliced parser should not return success, only slice")
          go(p.slice, 0).asInstanceOf[Result[List[A]]]
        else
          val buf = new collection.mutable.ListBuffer[A]
          def go(p: Parser[A], offset: Int): Result[List[A]] =
            p(s.advanceBy(offset)) match
              case Success(a,n) => buf += a; go(p, offset+n)
              case f@Failure(e,true) => f
              case Failure(e,_) => Success(buf.toList,offset)
              case Slice(n) =>
                buf += s.input.substring(offset,offset+n)
                go(p, offset+n)
          go(p, 0)

    def scope(msg: String): Parser[A] =
      s => p(s).mapError(_.push(s.loc,msg))

    def label(msg: String): Parser[A] =
      s => p(s).mapError(_.label(msg))

  def string(w: String): Parser[String] =
    s =>
      val i = firstNonmatchingIndex(s.loc.input, w, s.loc.offset)
      if i == -1 then // they matched
        if s.isSliced then Slice(w.length)
        else Success(w, w.length)
      else Failure(s.loc.advanceBy(i).toError("'" + w + "'"), i != 0)

  // note, regex matching is 'all-or-nothing' - failures are
  // uncommitted
  def regex(r: Regex): Parser[String] =
    s => r.findPrefixOf(s.input) match
      case None => Failure(s.loc.toError("regex " + r), false)
      case Some(m) =>
        if s.isSliced then Slice(m.length)
        else Success(m, m.length)

  def fail(msg: String): Parser[Nothing] =
    s => Failure(s.loc.toError(msg), true)

