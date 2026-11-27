// SPDX-License-Identifier: GPL-3.0-only
package my.ytmhu;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;

/** Reads line timing and keyed alternates from the Unison TTML variant. */
final class TtmlLyrics {
    static Lyrics parse(String xml) throws Exception {
        if (xml.length() > 512_000 || xml.contains("<!DOCTYPE")) throw new java.io.IOException("Unsupported TTML");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        javax.xml.parsers.DocumentBuilder parser = factory.newDocumentBuilder();
        parser.setEntityResolver((publicId, systemId) -> { throw new org.xml.sax.SAXException("External TTML entity"); });
        Element root = parser.parse(new InputSource(new StringReader(xml))).getDocumentElement();
        Map<String, Map<String, String>> translations = new HashMap<>();
        Map<String, String> roman = new HashMap<>();
        NodeList texts = root.getElementsByTagNameNS("*", "text");
        for (int i = 0; i < texts.getLength(); i++) {
            Element text = (Element) texts.item(i);
            if (!(text.getParentNode() instanceof Element)) continue;
            Element parent = (Element) text.getParentNode();
            String key = attribute(text, "for");
            if (key.isEmpty()) key = attribute(parent, "for");
            String value = text.getTextContent().trim();
            if (key.isEmpty() || value.isEmpty()) continue;
            if ("transliteration".equals(parent.getLocalName())) roman.put(key, value);
            else if ("translation".equals(parent.getLocalName())) {
                String lang = attribute(parent, "lang");
                if (lang.isEmpty() && parent.getParentNode() instanceof Element) {
                    lang = attribute((Element) parent.getParentNode(), "lang");
                }
                if (!lang.isEmpty()) translations.computeIfAbsent(key, unused -> new HashMap<>()).put(lang, value);
            }
        }
        List<Lyrics.Line> lines = new ArrayList<>();
        NodeList bodies = root.getElementsByTagNameNS("*", "body");
        if (bodies.getLength() > 0) {
            NodeList paragraphs = ((Element) bodies.item(0)).getElementsByTagNameNS("*", "p");
            for (int i = 0; i < paragraphs.getLength(); i++) {
                Element line = (Element) paragraphs.item(i);
                int begin = millis(attribute(line, "begin"));
                if (begin < 0) continue;
                String key = attribute(line, "key");
                if (key.isEmpty()) key = attribute(line, "id");
                lines.add(new Lyrics.Line(begin, line.getTextContent().trim(), roman.get(key), translations.get(key)));
            }
        }
        lines.sort(Comparator.comparingInt(line -> line.millis));
        return new Lyrics(lines, "", "Unison", attribute(root, "lang"));
    }

    private static String attribute(Element e, String local) {
        org.w3c.dom.NamedNodeMap attributes = e.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            org.w3c.dom.Node item = attributes.item(i);
            if (local.equals(item.getLocalName()) || local.equals(item.getNodeName())) return item.getNodeValue();
        }
        return "";
    }

    static int millis(String time) {
        try {
            double seconds;
            if (time.endsWith("ms")) return bounded(Double.parseDouble(time.substring(0, time.length() - 2)));
            if (time.endsWith("s")) seconds = Double.parseDouble(time.substring(0, time.length() - 1));
            else {
                if (!time.matches("(?:[0-9]{1,2}:){1,2}[0-5][0-9](?:\\.[0-9]+)?")) return -1;
                seconds = 0;
                for (String part : time.split(":")) seconds = seconds * 60 + Double.parseDouble(part);
            }
            return bounded(seconds * 1000);
        } catch (NumberFormatException invalid) { return -1; }
    }

    private static int bounded(double millis) {
        return millis >= 0 && millis <= Integer.MAX_VALUE ? (int) Math.round(millis) : -1;
    }
}
