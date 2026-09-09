package com.example.mdocreader

import uniffi.mdoc_reader_ffi.ReaderEventSink

class UiEventSink(private val listener: (String) -> Unit) : ReaderEventSink {
    override fun onEvent(event: String) = listener(event)
}
