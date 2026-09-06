package com.example.mdocreader

import com.example.mdocreader.rust.ReaderEventSink

class UiEventSink(private val listener: (String) -> Unit) : ReaderEventSink {
    override fun onEvent(event: String) = listener(event)
}
