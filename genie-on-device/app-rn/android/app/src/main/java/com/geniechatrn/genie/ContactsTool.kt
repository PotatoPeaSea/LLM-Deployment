package com.geniechatrn.genie

import android.Manifest
import android.content.Context
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject

/**
 * Look a person up in the device's address book.
 *
 * Deliberately narrow: it answers "what is X's number" and nothing else. There
 * is no way to ask it to dump the whole address book, because a model that can
 * enumerate every contact is one prompt injection away from reading them all
 * out. A blank query returns a refusal rather than everything.
 */
object ContactsTool : Tool {
    override val name = "search_contacts"
    override val permission = Manifest.permission.READ_CONTACTS

    private const val MAX_RESULTS = 5

    override fun schema() = functionSchema(
        name,
        "Look up a person in the user's contacts by name and return their " +
            "phone numbers and email addresses. Requires a name to search for.",
        JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("name", JSONObject().apply {
                    put("type", "string")
                    put("description", "Full or partial name of the person to look up.")
                })
            })
            put("required", JSONArray().put("name"))
        },
    )

    override fun run(context: Context, args: JSONObject): String {
        val query = args.optString("name").trim()
        if (query.isBlank()) {
            return "No name given. Ask the user which contact they mean; " +
                "listing all contacts is not supported."
        }

        val resolver = context.contentResolver
        val found = mutableListOf<String>()

        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
            "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?",
            arrayOf("%$query%"),
            "${ContactsContract.Contacts.DISPLAY_NAME} ASC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
            val nameCol = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)
            while (cursor.moveToNext() && found.size < MAX_RESULTS) {
                val id = cursor.getString(idCol)
                val display = cursor.getString(nameCol) ?: continue
                val phones = valuesFor(
                    context, ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID, id,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                )
                val emails = valuesFor(
                    context, ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    ContactsContract.CommonDataKinds.Email.CONTACT_ID, id,
                    ContactsContract.CommonDataKinds.Email.ADDRESS,
                )
                found.add(buildString {
                    append(display)
                    if (phones.isNotEmpty()) append(" — phone: ${phones.joinToString(", ")}")
                    if (emails.isNotEmpty()) append(" — email: ${emails.joinToString(", ")}")
                })
            }
        }

        return when {
            found.isEmpty() -> "No contact matching \"$query\"."
            else -> found.joinToString("\n")
        }
    }

    private fun valuesFor(
        context: Context,
        uri: android.net.Uri,
        idColumn: String,
        contactId: String,
        valueColumn: String,
    ): List<String> {
        val out = mutableListOf<String>()
        context.contentResolver.query(
            uri, arrayOf(valueColumn), "$idColumn = ?", arrayOf(contactId), null,
        )?.use { c ->
            val col = c.getColumnIndexOrThrow(valueColumn)
            while (c.moveToNext()) c.getString(col)?.let { if (it !in out) out.add(it) }
        }
        return out
    }
}
