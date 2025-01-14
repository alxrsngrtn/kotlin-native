/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package kotlinx.cli

import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlin.test.*

class ErrorTests {
    @Test
    fun testExtraArguments() {
        val argParser = ArgParser("testParser")
        val addendums by argParser.argument(ArgType.Int, "addendums", description = "Addendums").number(2)
        val output by argParser.argument(ArgType.String, "output", "Output file")
        val debugMode by argParser.option(ArgType.Boolean, "debug", "d", "Debug mode")
        val exception = assertFailsWith<IllegalStateException> { argParser.parse(
                arrayOf("2", "-d", "3", "out.txt", "something", "else", "in", "string")) }
        assertTrue("Too many arguments! Couldn't process argument something" in exception.message!!)
    }

    @Test
    fun testUnknownOption() {
        val argParser = ArgParser("testParser")
        val output by argParser.option(ArgType.String, "output", "o", "Output file")
        val input by argParser.option(ArgType.String, "input", "i", "Input file")
        val exception = assertFailsWith<IllegalStateException> {
            argParser.parse(arrayOf("-o", "out.txt", "-d", "-i", "input.txt"))
        }
        assertTrue("Unknown option -d" in exception.message!!)
    }

    @Test
    fun testWrongFormat() {
        val argParser = ArgParser("testParser")
        val number by argParser.option(ArgType.Int, "number", description = "Integer number")
        val exception = assertFailsWith<IllegalStateException> {
            argParser.parse(arrayOf("--number", "out.txt"))
        }
        assertTrue("Option number is expected to be integer number. out.txt is provided." in exception.message!!)
    }

    @Test
    fun testWrongChoice() {
        val argParser = ArgParser("testParser")
        val useShortForm by argParser.option(ArgType.Boolean, "short", "s", "Show short version of report").default(false)
        val renders by argParser.option(ArgType.Choice(listOf("text", "html")),
                "renders", "r", "Renders for showing information").multiple().default(listOf("text"))
        val exception = assertFailsWith<IllegalStateException> {
            argParser.parse(arrayOf("-r", "xml"))
        }
        assertTrue("Option renders is expected to be one of [text, html]. xml is provided." in exception.message!!)
    }
}
