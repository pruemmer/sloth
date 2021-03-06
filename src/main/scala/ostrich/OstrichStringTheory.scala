/**
 * This file is part of Ostrich.
 *
 * Copyright (C) 2018 Philipp Ruemmer <ph_r@gmx.net>
 *
 * Ostrich is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 2.1 of the License, or
 * (at your option) any later version.
 *
 * Ostrich is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Ostrich.  If not, see <http://www.gnu.org/licenses/>.
 */

package ostrich

import ap.Signature
import ap.basetypes.IdealInt
import ap.parser.{ITerm, IFormula, IExpression, IFunction}
import IExpression.Predicate
import ap.theories.strings._
import ap.theories.{Theory, ModuloArithmetic, TheoryRegistry, Incompleteness}
import ap.types.{Sort, MonoSortedIFunction, MonoSortedPredicate}
import ap.terfor.{Term, TermOrder}
import ap.terfor.conjunctions.Conjunction
import ap.terfor.preds.Atom
import ap.proof.theoryPlugins.Plugin
import ap.proof.goal.Goal
import ap.util.Seqs

import scala.collection.mutable.{HashMap => MHashMap}

object OstrichStringTheory {

  val alphabetSize = 1 << 16

}

////////////////////////////////////////////////////////////////////////////////

/**
 * The entry class of the Ostrich string solver.
 */
class OstrichStringTheory(transducers : Seq[(String, Transducer)],
                          flags : OFlags) extends {

  val alphabetSize = OstrichStringTheory.alphabetSize
  val upperBound = IdealInt(alphabetSize - 1)
  val CharSort   = ModuloArithmetic.ModSort(IdealInt.ZERO, upperBound)
  val RegexSort  = new Sort.InfUninterpreted("RegLan")

} with AbstractStringTheoryWithSort {

  private val CSo = CharSort
  private val SSo = StringSort
  private val RSo = RegexSort

  def int2Char(t : ITerm) : ITerm =
    ModuloArithmetic.cast2Interval(IdealInt.ZERO, upperBound, t)

  def char2Int(t : ITerm) : ITerm = t

  //////////////////////////////////////////////////////////////////////////////

  val str_reverse =
    MonoSortedIFunction("str.reverse", List(SSo), SSo, true, false)

  // List of user-defined functions that can be extended
  val extraFunctions : Seq[(String, IFunction, PreOp,
                            Atom => Seq[Term], Atom => Term)] =
    List(("str.reverse", str_reverse, ostrich.ReversePreOp,
          a => List(a(0)), a => a(1)))

  val extraFunctionPreOps =
    (for ((_, f, op, argSelector, resSelector) <- extraFunctions.iterator)
     yield (f, (op, argSelector, resSelector))).toMap

  val transducersWithPreds : Seq[(String, Predicate, Transducer)] =
    for ((name, transducer) <- transducers)
    yield (name, MonoSortedPredicate(name, List(SSo, SSo)), transducer)

  val transducerPreOps =
    (for ((_, p, transducer) <- transducersWithPreds.iterator)
     yield (p, TransducerPreOp(transducer))).toMap

  // Map used by the parser
  val extraOps : Map[String, Either[IFunction, Predicate]] =
    ((for ((name, f, _, _, _) <- extraFunctions.iterator)
      yield (name, Left(f))) ++
     (for ((name, p, _) <- transducersWithPreds.iterator)
      yield (name, Right(p)))).toMap

  //////////////////////////////////////////////////////////////////////////////

  val functions =
    predefFunctions ++ (extraFunctions map (_._2))

  val (funPredicates, _, _, functionPredicateMap) =
    Theory.genAxioms(theoryFunctions = functions)
  val predicates =
    predefPredicates ++ funPredicates ++ (transducersWithPreds map (_._2))

  val functionPredicateMapping = functions zip funPredicates
  val functionalPredicates = funPredicates.toSet
  val predicateMatchConfig : Signature.PredicateMatchConfig = Map()
  val axioms = Conjunction.TRUE
  val totalityAxioms = Conjunction.TRUE
  val triggerRelevantFunctions : Set[IFunction] = Set()

  override val dependencies : Iterable[Theory] = List(ModuloArithmetic)

  val _str_empty = functionPredicateMap(str_empty)
  val _str_cons  = functionPredicateMap(str_cons)
  val _str_++    = functionPredicateMap(str_++)

  private val predFunMap =
    (for ((f, p) <- functionPredicateMap) yield (p, f)).toMap

  object FunPred {
    def unapply(p : Predicate) : Option[IFunction] = predFunMap get p
  }

  // Set of the predicates that are fully supported at this point
  private val supportedPreds : Set[Predicate] =
    Set(str_in_re) ++
    (for (f <- Set(str_empty, str_cons,
                   str_++, str_len, str_replace, str_replaceall,
                   str_replacere, str_replaceallre, str_to_re,
                   re_none, re_eps, re_all, re_allchar, re_charrange,
                   re_++, re_union, re_inter, re_*, re_+, re_opt))
     yield functionPredicateMap(f)) ++
    (for ((_, e) <- extraOps.iterator) yield e match {
       case Left(f) => functionPredicateMap(f)
       case Right(p) => p
     })

  private val unsupportedPreds = predicates.toSet -- supportedPreds

  //////////////////////////////////////////////////////////////////////////////

  private val ostrichSolver = new OstrichSolver (this, flags)

  def plugin = Some(new Plugin {
    // not used
    def generateAxioms(goal : Goal)
          : Option[(Conjunction, Conjunction)] = None

    private val modelCache =
      new ap.util.LRUCache[Conjunction, Option[Map[Term, List[Int]]]](3)

    override def handleGoal(goal : Goal)
                       : Seq[Plugin.Action] = goalState(goal) match {

      case Plugin.GoalState.Final => { //  Console.withOut(Console.err) 
        breakCyclicEquations(goal) match {
          case Some(actions) =>
            actions
          case None =>
            modelCache(goal.facts) {
              ostrichSolver.findStringModel(goal) } match {
              case Some(m) => List()
              case None => List(Plugin.AddFormula(Conjunction.TRUE))
            }
        }
      }

      case _ => List()
    }

    override def generateModel(goal : Goal) : Option[Conjunction] =
      if (Seqs.disjointSeq(goal.facts.predicates, predicates))
        None
      else
        Some(assignStringValues(goal.facts,
                                (modelCache(goal.facts) {
                                  ostrichSolver.findStringModel(goal)
                                }).get,
                                goal.order))

  })

  //////////////////////////////////////////////////////////////////////////////

  override def isSoundForSat(
                 theories : Seq[Theory],
                 config : Theory.SatSoundnessConfig.Value) : Boolean =
    config match {
      case Theory.SatSoundnessConfig.Elementary  => true
      case Theory.SatSoundnessConfig.Existential => true
      case _                                     => false
    }

  override def preprocess(f : Conjunction, order : TermOrder) : Conjunction = {
    if (!Seqs.disjoint(f.predicates, unsupportedPreds))
      Incompleteness.set
    f
  }

  TheoryRegistry register this
  StringTheory register this

}