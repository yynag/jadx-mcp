package com.yyang.jadxmcp.analysis.metadata

import com.yyang.jadxmcp.protocol.ErrorCode
import com.yyang.jadxmcp.protocol.ProtocolError
import com.yyang.jadxmcp.protocol.ProtocolException
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

internal object SafeXml {
    fun parse(xml: String): Document {
        try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isValidating = false
                isXIncludeAware = false
                isExpandEntityReferences = false
                // Fail closed if a provider cannot enforce any security requirement.
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            }
            val builder = factory.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> throw IllegalArgumentException("External entities forbidden") }
            builder.setErrorHandler(object : ErrorHandler {
                override fun warning(e: SAXParseException) = Unit
                override fun error(e: SAXParseException): Unit = throw e
                override fun fatalError(e: SAXParseException): Unit = throw e
            })
            return builder.parse(InputSource(StringReader(xml)))
        } catch (e: Exception) {
            throw ProtocolException(ProtocolError(ErrorCode.INVALID_ARGUMENT, "Invalid or unsafe XML"))
        }
    }

    fun children(element: Element, tag: String? = null): List<Element> =
        (0 until element.childNodes.length).mapNotNull { element.childNodes.item(it) as? Element }
            .filter { tag == null || it.tagName == tag }

    fun android(element: Element, name: String): String =
        element.getAttributeNS("http://schemas.android.com/apk/res/android", name)
}
