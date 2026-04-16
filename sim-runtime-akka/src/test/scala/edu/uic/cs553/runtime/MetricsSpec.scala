package edu.uic.cs553.runtime

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

class MetricsSpec extends AnyWordSpec with Matchers with BeforeAndAfterEach:

  override def beforeEach(): Unit = MetricsCollector.reset()

  "MetricsCollector" should {

    "increment counters thread-safely" in {
      MetricsCollector.increment("test.counter")
      MetricsCollector.increment("test.counter")
      MetricsCollector.increment("test.counter")
      MetricsCollector.get("test.counter") shouldBe 3L
    }

    "return zero for unseen keys" in {
      MetricsCollector.get("never.touched") shouldBe 0L
    }

    "produce a non-empty snapshot after increments" in {
      MetricsCollector.increment("a")
      MetricsCollector.add("b", 5)
      val snap = MetricsCollector.snapshot()
      snap("a") shouldBe 1L
      snap("b") shouldBe 5L
    }
  }