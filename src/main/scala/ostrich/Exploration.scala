/*
 * This file is part of Sloth, an SMT solver for strings.
 * Copyright (C) 2018  Matthew Hague, Philipp Ruemmer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ostrich

import ap.SimpleAPI
import SimpleAPI.ProverStatus
import ap.basetypes.IdealInt
import ap.terfor.{Term, ConstantTerm, OneTerm}
import ap.terfor.linearcombination.LinearCombination
import ap.terfor.substitutions.VariableSubst
import ap.util.Seqs

import scala.collection.breakOut
import scala.collection.mutable.{HashMap => MHashMap, ArrayBuffer, ArrayStack,
                                 HashSet => MHashSet, LinkedHashSet,
                                 BitSet => MBitSet}

object Exploration {
  case class TermConstraint(t : Term, aut : Automaton)

  type ConflictSet = Seq[TermConstraint]

  abstract class ConstraintStore {
    def push : Unit

    def pop : Unit

    /**
     * Add new automata to the store, return a sequence of term constraints
     * in case the asserted constraints have become inconsistent
     */
    def assertConstraint(aut : Automaton) : Option[ConflictSet]

    /**
     * Return some representation of the asserted constraints
     */
    def getContents : List[Automaton]

    /**
     * Return all constraints that were asserted (without any modifications)
     */
    def getCompleteContents : List[Automaton]

    /**
     * Make sure that the exact length abstraction for the intersection of the
     * stored automata has been pushed to the length prover
     */
    def ensureCompleteLengthConstraints : Unit

    /**
     * Produce an arbitrary word accepted by all the stored constraints
     */
    def getAcceptedWord : Seq[Int]

    /**
     * Produce a word of length <code>len</code> accepted by all the stored
     * constraints
     */
    def getAcceptedWordLen(len : Int) : Seq[Int]
  }

  def eagerExp(funApps : Seq[(PreOp, Seq[Term], Term)],
               initialConstraints : Seq[(Term, Automaton)],
               concreteValues : Map[Term, Seq[Int]],
               lengthProver : Option[SimpleAPI],
               lengthVars : Map[Term, Term],
               strictLengths : Boolean,
               flags : OFlags) : Exploration =
    new EagerExploration(funApps, initialConstraints, concreteValues,
                         lengthProver, lengthVars, strictLengths, flags)

  def lazyExp(funApps : Seq[(PreOp, Seq[Term], Term)],
              initialConstraints : Seq[(Term, Automaton)],
              concreteValues : Map[Term, Seq[Int]],
              lengthProver : Option[SimpleAPI],
              lengthVars : Map[Term, Term],
              strictLengths : Boolean,
              flags : OFlags) : Exploration =
    new LazyExploration(funApps, initialConstraints, concreteValues,
                        lengthProver, lengthVars, strictLengths, flags)

  private case class FoundModel(model : Map[Term, Seq[Int]]) extends Exception
}

////////////////////////////////////////////////////////////////////////////////

/**
 * Depth-first exploration of a conjunction of function applications
 */
abstract class Exploration(val funApps : Seq[(PreOp, Seq[Term], Term)],
                           val initialConstraints : Seq[(Term, Automaton)],
                           concreteValues : Map[Term, Seq[Int]],
                           lengthProver : Option[SimpleAPI],
                           lengthVars : Map[Term, Term],
                           strictLengths : Boolean,
                           flags : OFlags) {

  import Exploration._

  def measure[A](op : String)(comp : => A) : A =
    if (flags.measureTimes)
      ap.util.Timer.measure(op)(comp)
    else
      comp

  Console.err.println
  Console.err.println("Running OSTRICH")

  // topological sorting of the function applications
  private val (allTerms, sortedFunApps)
              : (Set[Term], Seq[(Seq[(PreOp, Seq[Term])], Term)]) = {
    val argTermNum = new MHashMap[Term, Int]
    for ((_, _, res) <- funApps)
      argTermNum.put(res, 0)
    for ((t, _) <- initialConstraints)
      argTermNum.put(t, 0)
    for ((_, args, _) <- funApps; a <- args)
      argTermNum.put(a, argTermNum.getOrElse(a, 0) + 1)

    var remFunApps = funApps
    val sortedApps = new ArrayBuffer[(Seq[(PreOp, Seq[Term])], Term)]

    while (!remFunApps.isEmpty) {
      val (selectedApps, otherApps) =
        remFunApps partition { case (_, _, res) => argTermNum(res) == 0 }
      remFunApps = otherApps

      for ((_, args, _) <- selectedApps; a <- args)
        argTermNum.put(a, argTermNum.getOrElse(a, 0) - 1)

      assert(!selectedApps.isEmpty)

      val appsPerRes = selectedApps groupBy (_._3)
      val nonArgTerms = (selectedApps map (_._3)).distinct

      for (t <- nonArgTerms)
        sortedApps +=
          ((for ((op, args, _) <- appsPerRes(t)) yield (op, args), t))
    }

    (argTermNum.keySet.toSet, sortedApps.toSeq)
  }

  for ((ops, t) <- sortedFunApps)
    if (ops.size > 1 && !(concreteValues contains t))
      throw new Exception("Multiple definitions found for " + t)

  val leafTerms = allTerms -- (for ((_, t) <- sortedFunApps) yield t)

  Console.withOut(Console.err) {
    println("   Considered function applications:")
    for ((apps, res) <- sortedFunApps) {
      println("   " + res + " =")
      for ((op, args) <- apps)
        println("     " + op + "(" + (args mkString ", ") + ")")
    }
  }

  //////////////////////////////////////////////////////////////////////////////

  // check whether we have to add further regex constraints to ensure
  // completeness; otherwise not all pre-images of function applications might
  // be considered
  
  val allInitialConstraints = {
    val term2Index =
      (for (((_, t), n) <- sortedFunApps.iterator.zipWithIndex)
       yield (t -> n)).toMap

    val coveredTerms = new MBitSet
    for ((t, _) <- initialConstraints)
      for (ind <- term2Index get t)
        coveredTerms += ind

    val additionalConstraints = new ArrayBuffer[(Term, Automaton)]

    // check whether any of the terms have concrete definitions
    for (t <- allTerms)
      for (w <- concreteValues get t) {
        val str : String = w.map(i => i.toChar)(breakOut)
        additionalConstraints += ((t, BricsAutomaton fromString str))
        for (ind <- term2Index get t)
          coveredTerms += ind
      }

    for (n <- 0 until sortedFunApps.size;
         if {
           if (!(coveredTerms contains n)) {
             coveredTerms += n
             additionalConstraints +=
               ((sortedFunApps(n)._2, BricsAutomaton.makeAnyString()))
           }
           true
         };
         (_, args) <- sortedFunApps(n)._1;
         arg <- args)
      for (ind <- term2Index get arg)
        coveredTerms += ind

    initialConstraints ++ additionalConstraints
  }

  //////////////////////////////////////////////////////////////////////////////

  // set up the length prover with available information about the function
  // applications

  for (p <- lengthProver)
    for ((apps, res) <- sortedFunApps; (op, args) <- apps)
      p addAssertion op.lengthApproximation(args map lengthVars,
                                            lengthVars(res), p.order)

  //////////////////////////////////////////////////////////////////////////////

  private val constraintStores = new MHashMap[Term, ConstraintStore]

  def findModel : Option[Map[Term, Seq[Int]]] = {
    for (t <- allTerms)
      constraintStores.put(t, newStore(t))

    for ((t, aut) <- allInitialConstraints) {
      constraintStores(t).assertConstraint(aut) match {
        case Some(_) => return None
        case None    => // nothing
      }
    }

    for (p <- lengthProver) {
      for ((t, aut) <- allInitialConstraints)
        p addAssertion VariableSubst(0, List(lengthVars(t)), p.order)(
                                               aut.getLengthAbstraction)

      if (measure("check length consistency") { p.??? } == ProverStatus.Unsat)
        return None
    }

    if (flags.forwardApprox)
      addForwardConstraints

    val funAppList =
      (for ((apps, res) <- sortedFunApps;
            (op, args) <- apps)
       yield (op, args, res)).toList

    try {
      dfExplore(funAppList)
      None
    } catch {
      case FoundModel(model) => Some(model)
    }
  }

  /**
   * Propagates approximate constraints forwards from the root, adds new
   * constraints to constraintStores
   */
  private def addForwardConstraints : Unit = {
    for ((apps, res) <- sortedFunApps.reverseIterator;
         (op, args) <- apps) {
      val arguments = for (a <- args) yield constraintStores(a).getCompleteContents
      val resultConstraint = op.forwardApprox(arguments)
      constraintStores(res).assertConstraint(resultConstraint)
    }
  }

  private def evalTerm(t : Term)(model : SimpleAPI.PartialModel)
                      : Option[IdealInt] = t match {
    case c : ConstantTerm =>
      model eval c
    case OneTerm =>
      Some(IdealInt.ONE)
    case lc : LinearCombination => {
      val terms = for ((coeff, t) <- lc) yield (coeff, evalTerm(t)(model))
      if (terms forall { case (_, None) => false
                         case _ => true })
        Some((for ((coeff, Some(v)) <- terms) yield (coeff * v)).sum)
      else
        None
    }
  }

  private def dfExplore(apps : List[(PreOp, Seq[Term], Term)])
                      : ConflictSet = apps match {

    case List() => {
      // we are finished and just have to construct a model
      val model = new MHashMap[Term, Seq[Int]]

      val lengthModel =
        if (strictLengths) {
          for (t <- allTerms)
            constraintStores(t).ensureCompleteLengthConstraints
          for (core <- checkLengthConsistency)
            return core
          Some(lengthProver.get.partialModel)
        } else {
          None
        }

      def getVarLength(t : Term) : Option[IdealInt] =
        for (lModel <- lengthModel;
             lengthVar <- lengthVars get t;
             tlen <- evalTerm(lengthVar)(lModel))
        yield tlen

      for (t <- leafTerms) {
        val store = constraintStores(t)
        model.put(t,
                  (for (tlen <- getVarLength(t))
                   yield store.getAcceptedWordLen(tlen.intValueSafe))
                  .getOrElse(store.getAcceptedWord))
      }

      for ((ops, res) <- sortedFunApps.reverseIterator;
           (op, args) <- ops.iterator) {
        val resValue = op.eval(args map model) match {
          case Some(v) => v
          case None =>
            throw new Exception(
              "Model extraction failed: " + op + " is not defined for " +
              (args map model).mkString(", "))
        }

        for (oldValue <- model get res)
          if (resValue != oldValue)
            throw new Exception("Model extraction failed: " +
                                (oldValue mkString ", ") + " != " +
                                (resValue mkString ", "))

        for (resLen <- getVarLength(res))
          if (resValue.size != resLen.intValueSafe)
            throw new Exception(
              "Could not satisfy length constraints for " + res)

        model.put(res, resValue)
      }
      throw FoundModel(model.toMap)
    }
    case (op, args, res) :: otherApps =>
      dfExploreOp(op, args, res, constraintStores(res).getContents,
                  otherApps)
  }

  private def dfExploreOp(op : PreOp,
                          args : Seq[Term],
                          res : Term,
                          resConstraints : List[Automaton],
                          nextApps : List[(PreOp, Seq[Term], Term)])
                        : ConflictSet = resConstraints match {
    case List() =>
      dfExplore(nextApps)

    case resAut :: otherAuts => {
      ap.util.Timeout.check

      val argConstraints =
        for (a <- args) yield constraintStores(a).getCompleteContents

      val collectedConflicts = new LinkedHashSet[TermConstraint]

      val (newConstraintsIt, argDependencies) =
        measure("pre-op") { op(argConstraints, resAut) }
      while (measure("pre-op hasNext") {newConstraintsIt.hasNext}) {
        ap.util.Timeout.check

        val argCS = measure("pre-op next") {newConstraintsIt.next}

        for (a <- args)
          constraintStores(a).push

        pushLengthConstraints

        try {
          val newConstraints = new MHashSet[TermConstraint]

          var consistent = true
          for ((a, aut) <- args zip argCS)
            if (consistent) {
              newConstraints += TermConstraint(a, aut)

              constraintStores(a).assertConstraint(aut) match {
                case Some(conflict) => {
                  consistent = false

                  assert(!Seqs.disjointSeq(newConstraints, conflict))
                  collectedConflicts ++=
                    (conflict.iterator filterNot newConstraints)
                }
                case None => // nothing
              }
            }

          if (consistent)
            for (core <- checkLengthConsistency) {
//println("length conflict: " + core.size)
              collectedConflicts ++= (core.iterator filterNot newConstraints)
              consistent = false
            }

          if (consistent) {
            val conflict = dfExploreOp(op, args, res, otherAuts, nextApps)
            if (Seqs.disjointSeq(newConstraints, conflict)) {
              // we can jump back, because the found conflict does not depend
              // on the considered function application
//println("backjump " + (conflict map { case TermConstraint(t, aut) => (t, aut.hashCode) }))
              return conflict
            }
            collectedConflicts ++= (conflict.iterator filterNot newConstraints)
          }
        } finally {
          for (a <- args)
            constraintStores(a).pop
          popLengthConstraints
        }
      }

      if (needCompleteContentsForConflicts)
        collectedConflicts ++=
          (for (aut <- constraintStores(res).getCompleteContents)
           yield TermConstraint(res, aut))
      else
        collectedConflicts += TermConstraint(res, resAut)

      collectedConflicts ++=
        (for ((t, auts) <- args.iterator zip argDependencies.iterator;
              aut <- auts.iterator)
         yield TermConstraint(t, aut))

      collectedConflicts.toSeq
    }
  }

  //////////////////////////////////////////////////////////////////////////////

  private val lengthPartitionStack = new ArrayStack[Int]
  private val lengthPartitions = new ArrayBuffer[Seq[TermConstraint]]

  protected def addLengthConstraint(constraint : TermConstraint,
                                    sources : Seq[TermConstraint]) : Unit =
    for (p <- lengthProver) {
      lengthPartitions += sources
      p setPartitionNumber lengthPartitions.size
      val TermConstraint(t, aut) = constraint
      p addAssertion VariableSubst(0, List(lengthVars(t)), p.order)(
                                                  aut.getLengthAbstraction)
    }

  private def checkLengthConsistency : Option[Seq[TermConstraint]] =
    for (p <- lengthProver;
         if {
           measure("check length consistency") {p.???} == ProverStatus.Unsat
         }) yield {
      for (n <- p.getUnsatCore.toList.sorted;
           if n > 0;
           c <- lengthPartitions(n - 1))
      yield c
    }

  private def pushLengthConstraints : Unit =
    for (p <- lengthProver) {
      p.push
      lengthPartitionStack push lengthPartitions.size
    }

  private def popLengthConstraints : Unit =
    for (p <- lengthProver) {
      p.pop
      lengthPartitions reduceToSize lengthPartitionStack.pop
    }

  //////////////////////////////////////////////////////////////////////////////

  protected def newStore(t : Term) : ConstraintStore

  protected val needCompleteContentsForConflicts : Boolean

}

////////////////////////////////////////////////////////////////////////////////

/**
 * Version of exploration that eagerly computes products of regex constraints.
 */
class EagerExploration(_funApps : Seq[(PreOp, Seq[Term], Term)],
                       _initialConstraints : Seq[(Term, Automaton)],
                       _concreteValues : Map[Term, Seq[Int]],
                       _lengthProver : Option[SimpleAPI],
                       _lengthVars : Map[Term, Term],
                       _strictLengths : Boolean,
                       _flags : OFlags)
      extends Exploration(_funApps, _initialConstraints, _concreteValues,
                          _lengthProver, _lengthVars, _strictLengths, _flags) {

  import Exploration._

  protected val needCompleteContentsForConflicts : Boolean = true

  protected def newStore(t : Term) : ConstraintStore = new ConstraintStore {
    private val constraints = new ArrayBuffer[Automaton]
    private var currentConstraint : Option[Automaton] = None
    private val constraintStack = new ArrayStack[(Int, Option[Automaton])]

    def push : Unit =
      constraintStack push (constraints.size, currentConstraint)

    def pop : Unit = {
      val (oldSize, lastCC) = constraintStack.pop
      constraints reduceToSize oldSize
      currentConstraint = lastCC
    }

    def assertConstraint(aut : Automaton) : Option[ConflictSet] =
      if (aut.isEmpty) {
        Some(List(TermConstraint(t, aut)))
      } else {
        currentConstraint match {
          case Some(oldAut) => {
            val newAut = measure("intersection") { oldAut & aut }
            if (newAut.isEmpty) {
              Some(for (a <- aut :: constraints.toList)
                   yield TermConstraint(t, a))
            } else {
              constraints += aut
              currentConstraint = Some(newAut)
              addLengthConstraint(TermConstraint(t, newAut),
                                  for (a <- constraints)
                                  yield TermConstraint(t, a))
              None
            }
          }
          case None => {
            constraints += aut
            currentConstraint = Some(aut)
            val c = TermConstraint(t, aut)
            addLengthConstraint(c, List(c))
            None
          }
        }
      }

    def getContents : List[Automaton] =
      currentConstraint.toList
    def getCompleteContents : List[Automaton] =
      constraints.toList

    def ensureCompleteLengthConstraints : Unit = ()

    def getAcceptedWord : Seq[Int] =
      currentConstraint match {
        case Some(aut) => aut.getAcceptedWord.get
        case None      => List()
      }

    def getAcceptedWordLen(len : Int) : Seq[Int] =
      currentConstraint match {
        case Some(aut) => AutomataUtils.findAcceptedWord(List(aut), len).get
        case None      => List()
      }
  }

}

////////////////////////////////////////////////////////////////////////////////

/**
 * Version of exploration that keeps automata separate and avoids computation
 * of products. No caching yet
 */
class LazyExploration(_funApps : Seq[(PreOp, Seq[Term], Term)],
                      _initialConstraints : Seq[(Term, Automaton)],
                      _concreteValues : Map[Term, Seq[Int]],
                      _lengthProver : Option[SimpleAPI],
                      _lengthVars : Map[Term, Term],
                      _strictLengths : Boolean,
                      _flags : OFlags)
      extends Exploration(_funApps, _initialConstraints, _concreteValues,
                          _lengthProver, _lengthVars, _strictLengths, _flags) {

  import Exploration._

  protected val needCompleteContentsForConflicts : Boolean = false

  private val stores = new ArrayBuffer[Store]

  // Combinations of automata that are known to have empty intersection
  private val inconsistentAutomata = new ArrayBuffer[Seq[Automaton]]

  private def addIncAutomata(auts : Seq[Automaton]) : Unit = {
    inconsistentAutomata += auts
//    println("Watching " + inconsistentAutomata.size + " sets of inconsistent automata")
    val ind = inconsistentAutomata.size - 1
    for (s <- stores) {
      val r = s.watchAutomata(auts, ind)
      assert(r)
    }
  }

  protected def newStore(t : Term) : ConstraintStore = new Store(t)

  private class Store(t : Term) extends ConstraintStore {
    private val constraints = new ArrayBuffer[Automaton]
    private val constraintSet = new MHashSet[Automaton]
    private val constraintStack = new ArrayStack[Int]

    // Map from watched automata to the indexes of
    // <code>inconsistentAutomata</code> that is watched
    private val watchedAutomata = new MHashMap[Automaton, List[Int]]

    // Add a new entry to <code>watchedAutomata</code>; return
    // <code>false</code> in case the set of new automata is a subset of the
    // asserted constraints
    def watchAutomata(auts : Seq[Automaton], ind : Int) : Boolean =
      // TODO: we should randomise at this point!
      (auts find { a => !(constraintSet contains a) }) match {
        case Some(aut) => {
          watchedAutomata.put(aut,
                              ind :: watchedAutomata.getOrElse(aut, List()))
          true
        }
        case None =>
          false
      }

    def push : Unit = constraintStack push constraints.size

    def pop : Unit = {
      val oldSize = constraintStack.pop
      while (constraints.size > oldSize) {
        constraintSet -= constraints.last
        constraints reduceToSize (constraints.size - 1)
      }
    }

    def assertConstraint(aut : Automaton) : Option[ConflictSet] =
      if (constraintSet contains aut) {
        None
      } else {
        var potentialConflicts =
          (watchedAutomata get aut) match {
            case Some(incAuts) => {
              // need to find new watched automata for the found conflicts
              watchedAutomata -= aut
              incAuts
            }
            case None =>
              List()
          }

        while (!potentialConflicts.isEmpty) {
          val autInd = potentialConflicts.head

          if (!watchAutomata(inconsistentAutomata(autInd), autInd)) {
            // constraints have become inconsistent!
            watchedAutomata.put(aut, potentialConflicts)
            println("Stored conflict applies!")
            return Some(for (a <- inconsistentAutomata(autInd).toList)
                        yield TermConstraint(t, a))
          }

          potentialConflicts = potentialConflicts.tail
        }

        measure("AutomataUtils.findUnsatCore") { AutomataUtils.findUnsatCore(constraints, aut) } match {
          case Some(core) => {
            addIncAutomata(core)
            Some(for (a <- core.toList) yield TermConstraint(t, a))
          }
          case None => {
            constraints += aut
            constraintSet += aut
            val c = TermConstraint(t, aut)
            addLengthConstraint(c, List(c))
            None
          }
        }
      }

    def getContents : List[Automaton] =
      constraints.toList
    def getCompleteContents : List[Automaton] =
      constraints.toList

    private def intersection : Automaton = constraints reduceLeft (_ & _)

    def ensureCompleteLengthConstraints : Unit =
      constraints match {
        case Seq() | Seq(_) =>
          // nothing, all length constraints already pushed
        case auts =>
          addLengthConstraint(TermConstraint(t, intersection),
                              for (a <- constraints)
                              yield TermConstraint(t, a))
      }

    def getAcceptedWord : Seq[Int] =
      constraints match {
        case Seq() => List()
        case auts  => intersection.getAcceptedWord.get
      }

    def getAcceptedWordLen(len : Int) : Seq[Int] =
      constraints match {
        case Seq() => for (_ <- 0 until len) yield 0
        case auts  => AutomataUtils.findAcceptedWord(auts, len).get
      }
  }

}
