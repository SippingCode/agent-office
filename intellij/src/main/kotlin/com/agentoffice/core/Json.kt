package com.agentoffice.core

import com.google.gson.Gson
import com.google.gson.GsonBuilder

/** Shared JSON codec. Nulls are skipped so the view sees the same shape as the VS Code extension's snapshot. */
object Json {
    val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
}
