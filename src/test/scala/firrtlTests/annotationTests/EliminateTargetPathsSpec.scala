package firrtlTests.annotationTests

import firrtl.{ChirrtlForm, CircuitForm, CircuitState, LowFirrtlCompiler, LowFirrtlOptimization, LowForm, ResolvedAnnotationPaths, Transform}
import firrtl.annotations._
import firrtl.annotations.analysis.DuplicationHelper
import firrtl.transforms.DontTouchAnnotation
import firrtlTests.{FirrtlMatchers, FirrtlPropSpec}

class EliminateTargetPathsSpec extends FirrtlPropSpec with FirrtlMatchers {
  val input =
    """circuit Top:
      |  module Leaf:
      |    input i: UInt<1>
      |    output o: UInt<1>
      |    o <= i
      |    node a = i
      |  module Middle:
      |    input i: UInt<1>
      |    output o: UInt<1>
      |    inst l1 of Leaf
      |    inst l2 of Leaf
      |    l1.i <= i
      |    l2.i <= l1.o
      |    o <= l2.o
      |  module Top:
      |    input i: UInt<1>
      |    output o: UInt<1>
      |    inst m1 of Middle
      |    inst m2 of Middle
      |    m1.i <= i
      |    m2.i <= m1.o
      |    o <= m2.o
    """.stripMargin

  val TopCircuit = CircuitTarget("Top")
  val Top = TopCircuit.module("Top")
  val Middle = TopCircuit.module("Middle")
  val Leaf = TopCircuit.module("Leaf")

  val Top_m1_l1_a = Top.instOf("m1", "Middle").instOf("l1", "Leaf").ref("a")
  val Top_m2_l1_a = Top.instOf("m2", "Middle").instOf("l1", "Leaf").ref("a")
  val Top_m1_l2_a = Top.instOf("m1", "Middle").instOf("l2", "Leaf").ref("a")
  val Top_m2_l2_a = Top.instOf("m2", "Middle").instOf("l2", "Leaf").ref("a")
  val Middle_l1_a = Middle.instOf("l1", "Leaf").ref("a")
  val Middle_l2_a = Middle.instOf("l2", "Leaf").ref("a")
  val Leaf_a = Leaf.ref("a")

  case class DummyAnnotation(target: Target) extends SingleTargetAnnotation[Target] {
    override def duplicate(n: Target): Annotation = DummyAnnotation(target)
  }
  class DummyTransform() extends Transform with ResolvedAnnotationPaths {
    override def inputForm: CircuitForm = LowForm
    override def outputForm: CircuitForm = LowForm

    override val annotationClasses: Traversable[Class[_]] = Seq(classOf[DummyAnnotation])

    override def execute(state: CircuitState): CircuitState = state
  }
  val customTransforms = Seq(new DummyTransform())

  val inputState = CircuitState(parse(input), ChirrtlForm)
  property("Hierarchical tokens should be expanded properly") {
    val dupMap = new DuplicationHelper(inputState.circuit.modules.map(_.name).toSet)


    // Only a few instance references
    dupMap.expandHierarchy(Top_m1_l1_a)
    dupMap.expandHierarchy(Top_m2_l1_a)
    dupMap.expandHierarchy(Middle_l1_a)

    dupMap.makePathless(Top_m1_l1_a).foreach {Set(TopCircuit.module("Leaf___Top_m1_l1").ref("a")) should contain (_)}
    dupMap.makePathless(Top_m2_l1_a).foreach {Set(TopCircuit.module("Leaf___Top_m2_l1").ref("a")) should contain (_)}
    dupMap.makePathless(Top_m1_l2_a).foreach {Set(Leaf_a) should contain (_)}
    dupMap.makePathless(Top_m2_l2_a).foreach {Set(Leaf_a) should contain (_)}
    dupMap.makePathless(Middle_l1_a).foreach {Set(
      TopCircuit.module("Leaf___Top_m1_l1").ref("a"),
      TopCircuit.module("Leaf___Top_m2_l1").ref("a"),
      TopCircuit.module("Leaf___Middle_l1").ref("a")
    ) should contain (_) }
    dupMap.makePathless(Middle_l2_a).foreach {Set(Leaf_a) should contain (_)}
    dupMap.makePathless(Leaf_a).foreach {Set(
      TopCircuit.module("Leaf___Top_m1_l1").ref("a"),
      TopCircuit.module("Leaf___Top_m2_l1").ref("a"),
      TopCircuit.module("Leaf___Middle_l1").ref("a"),
      Leaf_a
    ) should contain (_)}
    dupMap.makePathless(Top).foreach {Set(Top) should contain (_)}
    dupMap.makePathless(Middle).foreach {Set(
      TopCircuit.module("Middle___Top_m1"),
      TopCircuit.module("Middle___Top_m2"),
      Middle
    ) should contain (_)}
    dupMap.makePathless(Leaf).foreach {Set(
      TopCircuit.module("Leaf___Top_m1_l1"),
      TopCircuit.module("Leaf___Top_m2_l1"),
      TopCircuit.module("Leaf___Middle_l1"),
      Leaf
    ) should contain (_) }

    val targets = Seq(Top_m1_l1_a, Top_m2_l1_a, Middle_l1_a, Middle_l2_a, Leaf_a, Top, Middle, Leaf)
    targets.foreach { t =>
      val newTargets = dupMap.makePathless(t)
      println(s"""${t.serialize} => \n${newTargets.map("    " + _.serialize).mkString("\n")}""")
    }
  }

  property("Hierarchical donttouch should be resolved properly") {
    val inputState = CircuitState(parse(input), ChirrtlForm, Seq(DontTouchAnnotation(Top_m1_l1_a)))
    val customTransforms = Seq(new LowFirrtlOptimization())
    val outputState = new LowFirrtlCompiler().compile(inputState, customTransforms)
    val check =
      """circuit Top :
        |  module Leaf___Top_m1_l1 :
        |    input i : UInt<1>
        |    output o : UInt<1>
        |
        |    node a = i
        |    o <= i
        |
        |  module Leaf :
        |    input i : UInt<1>
        |    output o : UInt<1>
        |
        |    skip
        |    o <= i
        |
        |  module Middle___Top_m1 :
        |    input i : UInt<1>
        |    output o : UInt<1>
        |
        |    inst l1 of Leaf___Top_m1_l1
        |    inst l2 of Leaf
        |    o <= l2.o
        |    l1.i <= i
        |    l2.i <= l1.o
        |
        |  module Middle :
        |    input i : UInt<1>
        |    output o : UInt<1>
        |
        |    inst l1 of Leaf
        |    inst l2 of Leaf
        |    o <= l2.o
        |    l1.i <= i
        |    l2.i <= l1.o
        |
        |  module Top :
        |    input i : UInt<1>
        |    output o : UInt<1>
        |
        |    inst m1 of Middle___Top_m1
        |    inst m2 of Middle
        |    o <= m2.o
        |    m1.i <= i
        |    m2.i <= m1.o
        |
      """.stripMargin
    canonicalize(outputState.circuit).serialize should be (canonicalize(parse(check)).serialize)
    outputState.annotations.collect{case x: DontTouchAnnotation => x.target} should be (Seq(Top.circuitTarget.module("Leaf___Top_m1_l1").ref("a")))
  }

  property("No name conflicts between old and new modules") {
    val input =
      """circuit Top:
        |  module Middle:
        |    input i: UInt<1>
        |    output o: UInt<1>
        |    o <= i
        |  module Top:
        |    input i: UInt<1>
        |    output o: UInt<1>
        |    inst m1 of Middle
        |    inst m2 of Middle
        |    inst x of Middle___Top_m1
        |    x.i <= i
        |    m1.i <= i
        |    m2.i <= m1.o
        |    o <= m2.o
        |  module Middle___Top_m1:
        |    input i: UInt<1>
        |    output o: UInt<1>
        |    o <= i
        |    node a = i
      """.stripMargin
    val checks =
      """circuit Top :
        |  module Middle :
        |  module Top :
        |  module Middle___Top_m1 :
        |  module Middle___Top_m1_1 :""".stripMargin.split("\n")
    val Top_m1 = Top.instOf("m1", "Middle")
    val inputState = CircuitState(parse(input), ChirrtlForm, Seq(DummyAnnotation(Top_m1)))
    val outputState = new LowFirrtlCompiler().compile(inputState, customTransforms)
    val outputLines = outputState.circuit.serialize.split("\n")
    checks.foreach { line =>
      outputLines should contain (line)
    }
  }

  property("Previously unused modules should remain, but newly unused modules should be eliminated") {
    val input =
      """circuit Top:
        |  module Leaf:
        |    input i: UInt<1>
        |    output o: UInt<1>
        |    o <= i
        |    node a = i
        |  module Middle:
        |    input i: UInt<1>
        |    output o: UInt<1>
        |    o <= i
        |  module Top:
        |    input i: UInt<1>
        |    output o: UInt<1>
        |    inst m1 of Middle
        |    inst m2 of Middle
        |    m1.i <= i
        |    m2.i <= m1.o
        |    o <= m2.o
      """.stripMargin

    val checks =
      """circuit Top :
        |  module Leaf :
        |  module Top :
        |  module Middle___Top_m1 :
        |  module Middle___Top_m2 :""".stripMargin.split("\n")

    val Top_m1 = Top.instOf("m1", "Middle")
    val Top_m2 = Top.instOf("m2", "Middle")
    val inputState = CircuitState(parse(input), ChirrtlForm, Seq(DummyAnnotation(Top_m1), DummyAnnotation(Top_m2)))
    val outputState = new LowFirrtlCompiler().compile(inputState, customTransforms)
    val outputLines = outputState.circuit.serialize.split("\n")

    checks.foreach { line =>
      outputLines should contain (line)
    }
    checks.foreach { line =>
      outputLines should not contain ("  module Middle :")
    }
  }
}