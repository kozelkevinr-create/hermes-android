package com.hermes.client.ui.matrix

/**
 * One entry of the Hermes slash-command catalog: the command name ("/model") plus a human
 * description. Fetched best-effort from the gateway's commands.catalog RPC; over Matrix the whole
 * catalog is sent as text messages for the bot to parse (identical to the gateway chat's rule).
 */
data class CommandEntry(val name: String, val description: String)

/**
 * The palette query while the draft is composing a slash command: the text typed after the "/"
 * (lowercased), but only while the first word is still being typed — a draft like "/model fast"
 * is a complete command and hides the palette. Everything else returns null so the palette logic
 * is a single decision point for the UI.
 */
fun commandPaletteQuery(draft: String): String? {
    if (!draft.startsWith("/") || draft.contains(' ')) return null
    return draft.drop(1).lowercase()
}

/**
 * Commands matching the active [commandPaletteQuery]: case-insensitive prefix match on the
 * command name without its leading slash (the gateway chat's rule). A null query yields nothing:
 * the palette only exists while a slash word is being composed. Order is preserved so the
 * catalog's own ranking survives.
 */
fun filterCommands(commands: List<CommandEntry>, query: String?): List<CommandEntry> {
    if (query == null) return emptyList()
    val q = query.lowercase()
    return commands.filter { it.name.removePrefix("/").lowercase().startsWith(q) }
}

/** The draft a palette tap inserts: the command with its slash guaranteed, plus one trailing
 *  space so the next keystroke starts an argument (or a plain Send fires the bare command). */
fun commandDraft(name: String): String =
    (if (name.startsWith("/")) name else "/$name") + " "

/**
 * The wire string the gateway chat sends for a SESSION-scoped model switch ("/model <model>
 * --provider <provider> --session"); note the model comes first. The bot parses the identical
 * text message, so the room's model picker sends exactly this string.
 */
fun modelCommand(provider: String, model: String): String =
    "/model $model --provider $provider --session"
