package com.example.mdocreader

import org.json.JSONArray
import org.json.JSONObject

data class RequestedField(val documentIndex: Int, val namespace: String, val name: String)

fun requestedFields(request: String): List<RequestedField> = buildList {
    val documents = JSONObject(request).getJSONArray("docRequests")
    for (index in 0 until documents.length()) {
        val namespaces = documents.getJSONObject(index).getJSONObject("itemsRequest").getJSONObject("nameSpaces")
        for (namespace in namespaces.keys()) {
            for (name in namespaces.getJSONObject(namespace).keys()) add(RequestedField(index, namespace, name))
        }
    }
}

fun selectedRequest(request: String, selected: Set<RequestedField>): String {
    require(selected.isNotEmpty()) { "Select at least one item." }
    val root = JSONObject(request)
    val documents = root.getJSONArray("docRequests")
    val filtered = JSONArray()
    for (index in 0 until documents.length()) {
        val document = documents.getJSONObject(index)
        val namespaces = document.getJSONObject("itemsRequest").getJSONObject("nameSpaces")
        for (namespace in namespaces.keys().asSequence().toList()) {
            val fields = namespaces.getJSONObject(namespace)
            for (name in fields.keys().asSequence().toList()) {
                if (RequestedField(index, namespace, name) !in selected) fields.remove(name)
            }
            if (fields.length() == 0) namespaces.remove(namespace)
        }
        if (namespaces.length() > 0) filtered.put(document)
    }
    require(filtered.length() > 0) { "Select at least one item." }
    root.put("docRequests", filtered)
    return root.toString()
}
