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

import org.assertj.core.api.Assertions.assertThat
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.PolyglotAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.io.IOAccess
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PolyglotTest {
  @Test
  fun `objects are lazy read only and preserve identity`() {
    PolyglotContext.newBuilder("pkl").build().use { context ->
      val module =
        context.eval(
          "pkl",
          """
          local secret = 1
          hidden invisible = 2
          answer = 42
          child = new Dynamic { name = "bird" }
          alias = child
          self = this
          bad = throw("not evaluated")
          absent = null
          """
            .trimIndent(),
        )
      assertThat(module.memberKeys).contains("answer", "child", "alias", "self", "bad", "absent")
      assertThat(module.memberKeys).doesNotContain("secret", "invisible")
      assertThat(module.hasMember("secret")).isFalse()
      assertThat(module.hasMember("invisible")).isFalse()
      assertThat(module.getMember("missing")).isNull()
      assertThat(module.getMember("answer").asLong()).isEqualTo(42)
      assertThat(module.getMember("child").getMember("name").asString()).isEqualTo("bird")
      assertThat(module.getMember("child")).isEqualTo(module.getMember("alias"))
      assertThat(module.getMember("self")).isEqualTo(module)
      assertThat(module.getMember("absent").isNull).isTrue()
      assertThat(module.toString()).contains("Pkl")
      assertThrows<UnsupportedOperationException> { module.putMember("answer", 7) }
      assertThrows<UnsupportedOperationException> { module.removeMember("answer") }
      assertThat(module.getMember("answer").asLong()).isEqualTo(42)
      val error = assertThrows<PolyglotException> { module.getMember("bad") }
      assertThat(error.isGuestException).isTrue()
      assertThat(error.isInternalError).isFalse()
    }
  }

  @Test
  fun `collections retain distinct elements entries and properties`() {
    PolyglotContext.newBuilder("pkl").build().use { context ->
      val module =
        context.eval(
          "pkl",
          """
          list = List(1, 2, new Dynamic { name = "bird" })
          listing = new Listing { 3; 4 }
          mapping = new Mapping { ["key"] = 5; [42] = null }
          map = Map("key", 6, 42, "number")
          set = Set(1, 2, 1)
          mixed = new Dynamic { title = "mixed"; 7; ["key"] = 8 }
          duration = 3.ms
          size = 2.kib
          pair = Pair("a", 9)
          """
            .trimIndent(),
        )
      val list = module.getMember("list")
      assertThat(list.arraySize).isEqualTo(3)
      assertThat(list.getArrayElement(2).getMember("name").asString()).isEqualTo("bird")
      assertThrows<ArrayIndexOutOfBoundsException> { list.getArrayElement(-1) }
      assertThrows<ArrayIndexOutOfBoundsException> { list.getArrayElement(3) }
      assertThrows<UnsupportedOperationException> { list.setArrayElement(0, 9) }
      assertThat(module.getMember("listing").getArrayElement(1).asLong()).isEqualTo(4)
      val mapping = module.getMember("mapping")
      assertThat(mapping.hashSize).isEqualTo(2)
      assertThat(mapping.getHashValue("key").asLong()).isEqualTo(5)
      assertThat(mapping.getHashValue(42).isNull).isTrue()
      assertThat(mapping.hasHashEntry("missing")).isFalse()
      assertThrows<UnsupportedOperationException> { mapping.putHashEntry("key", 9) }
      val map = module.getMember("map")
      assertThat(map.getHashValue(42).asString()).isEqualTo("number")
      val entries = map.hashEntriesIterator
      assertThat(entries.iteratorNextElement.getArrayElement(0).asString()).isEqualTo("key")
      assertThat(entries.iteratorNextElement.getArrayElement(1).asString()).isEqualTo("number")
      assertThat(entries.hasIteratorNextElement()).isFalse()
      val set = module.getMember("set").iterator
      assertThat(set.iteratorNextElement.asLong()).isEqualTo(1)
      assertThat(set.iteratorNextElement.asLong()).isEqualTo(2)
      assertThat(set.hasIteratorNextElement()).isFalse()
      val mixed = module.getMember("mixed")
      assertThat(mixed.getMember("title").asString()).isEqualTo("mixed")
      assertThat(mixed.getArrayElement(0).asLong()).isEqualTo(7)
      assertThat(mixed.getHashValue("key").asLong()).isEqualTo(8)
      assertThat(mixed.hashSize).isEqualTo(1)
      assertThat(module.getMember("duration").getMember("unit").asString()).isEqualTo("ms")
      assertThat(module.getMember("size").getMember("value").asDouble()).isEqualTo(2.0)
      assertThat(module.getMember("pair").getMember("second").asLong()).isEqualTo(9)
    }
  }

  @Test
  fun `hash keys retain types and exact numeric values`() {
    PolyglotContext.newBuilder("pkl").build().use { context ->
      val module =
        context.eval(
          "pkl",
          """
          key = new Dynamic { name = "key" }
          map = Map(key, "object", null, "null", true, "boolean", 1.5, "float", 9007199254740993, "large")
          large = 9007199254740993
          """
            .trimIndent(),
        )
      val map = module.getMember("map")
      assertThat(map.getHashValue(module.getMember("key")).asString()).isEqualTo("object")
      assertThat(map.getHashValue(null).asString()).isEqualTo("null")
      assertThat(map.getHashValue(true).asString()).isEqualTo("boolean")
      assertThat(map.getHashValue(1.5).asString()).isEqualTo("float")
      assertThat(map.getHashValue(9007199254740993L).asString()).isEqualTo("large")
      assertThat(module.getMember("large").asLong()).isEqualTo(9007199254740993L)
      assertThat(map.hasHashEntry("true")).isFalse()
      val entries = map.hashEntriesIterator
      while (entries.hasIteratorNextElement()) {
        val entry = entries.iteratorNextElement
        assertThat(map.getHashValue(entry.getArrayElement(0))).isEqualTo(entry.getArrayElement(1))
      }
    }
  }

  @Test
  fun `reads retain type constraints and enumeration does not force values`() {
    PolyglotContext.newBuilder("pkl").build().use { context ->
      val module =
        context.eval(
          "pkl",
          """
          good = 1
          bad: Int = "wrong"
          listing: Listing<Int> = new { "wrong" }
          mapping = new Mapping { ["good"] = 1; ["bad"] = throw("lazy") }
          """
            .trimIndent(),
        )
      assertThat(module.memberKeys).contains("bad")
      assertThat(module.getMember("good").asInt()).isEqualTo(1)
      assertThrows<PolyglotException> { module.getMember("bad") }
      val listing = module.getMember("listing")
      assertThat(listing.arraySize).isEqualTo(1)
      assertThrows<PolyglotException> { listing.getArrayElement(0) }
      val mapping = module.getMember("mapping")
      assertThat(mapping.hashSize).isEqualTo(2)
      assertThat(mapping.hasHashEntry("bad")).isTrue()
      assertThat(mapping.getHashValue("good").asInt()).isEqualTo(1)
      assertThrows<PolyglotException> { mapping.getHashValue("bad") }
    }
  }

  @Test
  fun `source caching does not reuse module instances across evaluations or contexts`() {
    val source = Source.newBuilder("pkl", "child = new Dynamic { n = 1 }", "test.pkl").build()
    PolyglotContext.newBuilder("pkl").build().use { first ->
      val a = first.eval(source)
      val b = first.eval(source)
      assertThat(a).isNotEqualTo(b)
      assertThat(a.getMember("child").getMember("n").asInt()).isEqualTo(1)
      PolyglotContext.newBuilder("pkl").build().use { second ->
        assertThat(second.eval(source).getMember("child").getMember("n").asInt()).isEqualTo(1)
      }
      assertThat(a.getMember("child").getMember("n").asInt()).isEqualTo(1)
    }
  }

  @Test
  fun `independent engines support stdlib calls and different host access policies`() {
    Context.newBuilder("pkl").allowAllAccess(true).build().use { first ->
      Context.create("pkl").use { second ->
        for (context in listOf(first, second, first)) {
          val module = context.eval("pkl", "import \"pkl:math\"; pair = Pair(math.sqrt(9), 2)")
          assertThat(module.getMember("pair").getMember("first").asDouble()).isEqualTo(3.0)
        }
      }
      assertThat(first.eval("pkl", "n = List(1, 2).length").getMember("n").asInt()).isEqualTo(2)
    }
  }

  @Test
  fun `values cannot be used after context close`() {
    val context = PolyglotContext.newBuilder("pkl").build()
    val module = context.eval("pkl", "n = 1")
    context.close()
    assertThrows<IllegalStateException> { module.getMember("n") }
  }

  @Test
  fun `standalone evaluation allows stdlib but does not inherit ambient IO`() {
    PolyglotContext.newBuilder("pkl").allowIO(IOAccess.ALL).build().use { context ->
      assertThat(
          context.eval("pkl", "import \"pkl:math\"; n = math.sqrt(9)").getMember("n").asDouble()
        )
        .isEqualTo(3.0)
      for (source in
        listOf(
          "import \"file:/etc/passwd\"; n = 1",
          "import \"https://example.com/module.pkl\"; n = 1",
          "n = read(\"env:HOME\")",
          "n = read(\"file:/etc/passwd\")",
        )) {
        assertThrows<PolyglotException> { context.eval("pkl", source).getMember("n") }
      }
    }
  }

  @Test
  fun `JavaScript can evaluate and consume Pkl objects without a conversion layer`() {
    PolyglotContext.newBuilder("js", "pkl").allowPolyglotAccess(PolyglotAccess.ALL).build().use {
      context ->
      val result =
        context.eval(
          "js",
          """
          const config = Polyglot.eval('pkl', `
            title = "birds"
            nested = new Dynamic { count = 2 }
            alias = nested
            values = List(3, 4)
            lookup = Map("bird", "owl", 10000000000, "large", 1.0, "float")
            unique = Set(3, 4, 3)
            absent = null
            broken = throw("lazy")
          `);
          const keys = Object.keys(config);
          [config.title === 'birds',
           config.nested.count === 2,
           config.nested === config.alias,
           config.values[0] + config.values[1] === 7,
           Array.from(config.values).join(',') === '3,4',
           config.absent == null,
           config.lookup.get('bird') === 'owl',
           config.lookup.get(10000000000) === 'large',
           config.lookup.get(1) === 'float',
           JSON.stringify(config.nested) === '{"count":2}',
           Array.from(config.unique).join(',') === '3,4',
           keys.includes('broken'),
           (() => { try { config.title = 'changed'; } catch (_) {} return config.title === 'birds'; })()
          ].every(Boolean);
          """
            .trimIndent(),
        )
      assertThat(result.asBoolean()).isTrue()
    }
  }
}
