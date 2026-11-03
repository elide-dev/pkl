/*
 * Copyright © 2024-2026 Apple Inc. and the Pkl project authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.pkl.core

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.assertj.core.api.Assertions.assertThat
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.PolyglotAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PolyglotExecutionTest {
  private fun context() =
    Context.newBuilder("js", "pkl").allowPolyglotAccess(PolyglotAccess.ALL).build()

  @Test
  fun `functions execute with type checks closures objects and precise primitives`() {
    context().use { context ->
      val module =
        context.eval(
          "pkl",
          """
          prefix = "Hello, "
          greeting = (name: String) -> prefix + name
          sum = (a: Int, b: Int) -> a + b
          identity = (value) -> value
          positive = (value: Int(isPositive)) -> value
          make = (name: String) -> new Dynamic { value = name }
          empty = () -> 42
          function wrong(value: Int): String = value
          bad = (value) -> wrong(value)
          """
            .trimIndent(),
        )
      assertThat(module.getMember("greeting").canExecute()).isTrue()
      assertThat(module.getMember("greeting").execute("Sam").asString()).isEqualTo("Hello, Sam")
      assertThat(module.getMember("sum").execute(3, 4).asLong()).isEqualTo(7)
      assertThat(module.getMember("identity").execute(9007199254740993L).asLong())
        .isEqualTo(9007199254740993L)
      assertThat(module.getMember("identity").execute(null as Any?).isNull).isTrue()
      assertThat(module.getMember("empty").execute().asInt()).isEqualTo(42)
      val child = module.getMember("make").execute("bird")
      assertThat(child.getMember("value").asString()).isEqualTo("bird")
      assertThat(module.getMember("identity").execute(child)).isEqualTo(child)
      assertThrows<IllegalArgumentException> { module.getMember("sum").execute(1) }
      assertThrows<IllegalArgumentException> { module.getMember("sum").execute(1, 2, 3) }
      val mismatch = assertThrows<PolyglotException> { module.getMember("sum").execute("wrong", 1) }
      assertThat(mismatch.isInternalError).isFalse()
      assertThat(mismatch.message).contains("Int", "String")
      assertThrows<PolyglotException> { module.getMember("positive").execute(-1) }
      assertThrows<PolyglotException> { module.getMember("bad").execute(1) }
      val foreignObject = context.eval("js", "({name: 'bird'})")
      val foreignFunction = context.eval("js", "(x) => x")
      assertThrows<IllegalArgumentException> { module.getMember("identity").execute(foreignObject) }
      assertThrows<IllegalArgumentException> {
        module.getMember("identity").execute(foreignFunction)
      }
    }
  }

  @Test
  fun `JavaScript invokes Pkl functions and snapshots are isolated from later writes`() {
    context().use { context ->
      context.getBindings("js").putMember("Pkl", context.getBindings("pkl"))
      val result =
        context.eval(
          "js",
          """
          const functions = Polyglot.eval('pkl', `
            greeting = (name: String) -> "Hello, " + name
            describe = (data: Dynamic) -> data.child.name + data.items[0].toString()
            identity = (value) -> value
          `);
          const child = {name: 'bird'};
          const source = {child, alias: child, items: [7, 8], map: new Map([['bird', 2]])};
          const saved = Pkl.snapshot(source);
          source.child.name = 'changed'; source.items[0] = 99; source.map.set('bird', 100);
          [functions.greeting('Sam') === 'Hello, Sam',
           functions.describe(saved) === 'bird7',
           saved.child === saved.alias,
           saved.map.get('bird') === 2,
           functions.identity(saved) === saved,
           (() => { try { saved.child.name = 'bad'; } catch (_) {} return saved.child.name === 'bird'; })()
          ].every(Boolean);
          """
            .trimIndent(),
        )
      assertThat(result.asBoolean()).isTrue()
    }
  }

  @Test
  fun `snapshot rejects cyclic graphs functions accessors and lossy numbers`() {
    context().use { context ->
      val snapshot = context.getBindings("pkl").getMember("snapshot")
      for ((script, expected) in
        listOf(
          "(() => { let x = {}; x.self = x; return x; })()" to "Cycle",
          "({callback: () => 1})" to "Functions",
          "({get value() { throw Error('must not run'); }})" to "Accessor",
          "18446744073709551617n" to "losslessly",
          "new Map([[{x: 1}, 'a'], [{x: 1}, 'b']])" to "equal Pkl keys",
          "(() => { let x = {}; for (let i = 0; i < 300; i++) x = {next: x}; return x; })()" to
            "depth",
          "new Array(100001).fill(1)" to "size limit",
        )) {
        val value = context.eval("js", script)
        val error = assertThrows<IllegalArgumentException>(script) { snapshot.execute(value) }
        assertThat(error.message).contains(expected)
      }
      assertThrows<IllegalArgumentException> { snapshot.execute() }
      assertThrows<IllegalArgumentException> { snapshot.execute(1, 2) }
      // A failed copy never poisons subsequent snapshots or the evaluation context.
      val recovered = snapshot.execute(context.eval("js", "({value: 42})"))
      assertThat(recovered.getMember("value").asInt()).isEqualTo(42)
    }
  }

  @Test
  fun `snapshot handles map keys null arrays and iterables without retaining guest objects`() {
    context().use { context ->
      val snapshot = context.getBindings("pkl").getMember("snapshot")
      val result =
        snapshot.execute(
          context.eval(
            "js",
            """
            ({empty: {}, list: [null, true, 1.5, 'x'], sparse: [1, , 3], set: new Set([1, 2]),
              map: new Map([[null, 'nil'], [true, 'yes'], [3, 'three']])})
            """
              .trimIndent(),
          )
        )
      assertThat(result.getMember("empty").memberKeys).isEmpty()
      assertThat(result.getMember("sparse").getArrayElement(1).isNull).isTrue()
      assertThat(result.getMember("list").getArrayElement(0).isNull).isTrue()
      assertThat(result.getMember("list").getArrayElement(1).asBoolean()).isTrue()
      assertThat(result.getMember("list").getArrayElement(2).asDouble()).isEqualTo(1.5)
      assertThat(result.getMember("set").arraySize).isEqualTo(2)
      assertThat(result.getMember("map").getHashValue(null).asString()).isEqualTo("nil")
      assertThat(result.getMember("map").getHashValue(true).asString()).isEqualTo("yes")
      assertThat(result.getMember("map").getHashValue(3).asString()).isEqualTo("three")
    }
  }

  @Test
  fun `Pkl arguments cannot bypass context ownership even on the same engine`() {
    Engine.create().use { engine ->
      Context.newBuilder("pkl").engine(engine).build().use { first ->
        Context.newBuilder("pkl").engine(engine).build().use { second ->
          val identity = first.eval("pkl", "identity = (value) -> value").getMember("identity")
          val foreign = second.eval("pkl", "child = new Dynamic { n = 1 }").getMember("child")
          assertThrows<IllegalArgumentException> { identity.execute(foreign) }
        }
      }
    }
  }

  @Test
  fun `snapshots do not retain a source context`() {
    Context.create("pkl").use { target ->
      val copied =
        Context.create("js").use { source ->
          PolyglotContext.snapshot(target, source.eval("js", "({child: {n: 7}, list: [1, 2]})"))
        }
      assertThat(copied.getMember("child").getMember("n").asInt()).isEqualTo(7)
      assertThat(copied.getMember("list").getArrayElement(1).asInt()).isEqualTo(2)
      val identity =
        target.eval("pkl", "identity = (value: Dynamic) -> value").getMember("identity")
      assertThat(identity.execute(copied)).isEqualTo(copied)
    }
  }

  @Test
  fun `shared engine contexts initialize concurrently and remain isolated`() {
    Engine.create().use { engine ->
      val executor = Executors.newFixedThreadPool(3)
      try {
        val tasks =
          (1..6).map { n ->
            Callable {
              Context.newBuilder("pkl").engine(engine).build().use { context ->
                val module =
                  context.eval(
                    "pkl",
                    "import \"pkl:semver\"; v = semver.Version(\"1.2.3\"); n = $n",
                  )
                assertThat(module.getMember("v").getMember("minor").asInt()).isEqualTo(2)
                assertThat(module.getMember("n").asInt()).isEqualTo(n)
              }
            }
          }
        for (future in executor.invokeAll(tasks)) future.get()
      } finally {
        executor.shutdownNow()
      }
    }
  }

  @Test
  fun `Java snapshot helper and invocation metadata preserve lazy evaluation`() {
    context().use { context ->
      val module =
        context.eval(
          "pkl",
          """
          identity = (value) -> value
          data = "not callable"
          bad = throw("lazy")
          """
            .trimIndent(),
        )
      // Discovering invocation support must not evaluate the failing property.
      assertThat(module.canInvokeMember("bad")).isTrue()
      assertThat(module.canInvokeMember("identity")).isTrue()
      val saved = PolyglotContext.snapshot(context, context.eval("js", "({name: 'bird'})"))
      assertThat(module.invokeMember("identity", saved)).isEqualTo(saved)
      assertThrows<IllegalArgumentException> { module.invokeMember("data") }
      assertThat(module.canInvokeMember("data")).isFalse()
      assertThat(module.getMember("identity").execute(saved).getMember("name").asString())
        .isEqualTo("bird")
    }
  }

  @Test
  fun `engine local standard libraries support independent parallel evaluation and shutdown`() {
    val source =
      Source.newBuilder(
          "pkl",
          """
          import "pkl:math"
          import "pkl:reflect"
          import "pkl:semver"
          import "pkl:net"
          n = math.sqrt(16)
          pair = Pair(n, 5)
          version = semver.Version("1.2.3")
          url = net.Url("https://example.com/test")
          reflection = reflect.Class(String).name
          emptyDynamic = new Dynamic {}
          emptyListing = new Listing {}
          emptyMapping = new Mapping {}
          identity = (value) -> value
          """
            .trimIndent(),
          "engines.pkl",
        )
        .build()
    val executor = Executors.newFixedThreadPool(3)
    try {
      val tasks =
        (1..6).map {
          Callable {
            Context.create("pkl").use { context ->
              val module = context.eval(source)
              assertThat(module.getMember("pair").getMember("first").asDouble()).isEqualTo(4.0)
              assertThat(module.getMember("version").getMember("major").asLong()).isEqualTo(1)
              assertThat(module.getMember("url").getMember("rawHost").asString())
                .isEqualTo("example.com")
              assertThat(module.getMember("reflection").asString()).isEqualTo("String")
              val empty = module.getMember("emptyDynamic")
              assertThat(module.getMember("identity").execute(empty)).isEqualTo(empty)
              assertThat(module.getMember("emptyListing").arraySize).isZero()
              assertThat(module.getMember("emptyMapping").hashSize).isZero()
            }
          }
        }
      for (future in executor.invokeAll(tasks)) future.get()
    } finally {
      executor.shutdownNow()
    }
    // Closing unrelated engines must not invalidate the original evaluator path.
    Evaluator.preconfigured().use { evaluator ->
      assertThat(evaluator.evaluate(ModuleSource.text("n = 1")).getProperty("n")).isEqualTo(1L)
    }
  }
}
