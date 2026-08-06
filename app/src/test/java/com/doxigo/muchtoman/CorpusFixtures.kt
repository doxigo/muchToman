package com.doxigo.muchtoman

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * The golden corpus, loaded as though it had been ingested.
 *
 * Shared so that the parser tests and the ledger tests read exactly the same messages. Anything
 * downstream of the parser gets to be exercised against real bank text rather than invented
 * text, which is the only kind that has ever surprised this codebase.
 */
object CorpusFixtures {

    fun files(): List<File> {
        val dir = checkNotNull(javaClass.getResource("/sms")) {
            "app/src/test/resources/sms is not on the test classpath"
        }
        val files = checkNotNull(File(dir.toURI()).listFiles { f: File -> f.name.endsWith(".json") }) {
            "no corpus files under $dir"
        }
        return files.sortedBy { it.name }
    }

    /** Every case in the corpus as an [SmsSource], keyed exactly as ingest would key it. */
    fun sources(): List<SmsSource> = files().flatMap { file ->
        Json.parseToJsonElement(file.readText()).jsonObject["cases"]!!.jsonArray.map { element ->
            val case = element.jsonObject
            val sender = case["sender"]!!.jsonPrimitive.content
            val at = case["at"]?.jsonPrimitive?.content?.toLong() ?: 1L
            val body = case["body"]!!.jsonArray.joinToString("\n") { it.jsonPrimitive.content }
            SmsSource(
                srcHash = srcHash(sender, body, at),
                sender = sender,
                addrKey = srcAddrKeyV1(sender),
                body = body,
                at = at,
                ingestedAt = 0L,
            )
        }
    }
}
