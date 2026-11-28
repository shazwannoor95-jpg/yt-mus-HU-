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
        Map<String, Integer> voices = new HashMap<>();
        NodeList bodies = root.getElementsByTagNameNS("*", "body");
        if (bodies.getLength() > 0) {
            NodeList paragraphs = ((Element) bodies.item(0)).getElementsByTagNameNS("*", "p");
            for (int i = 0; i < paragraphs.getLength(); i++) {
                Element line = (Element) paragraphs.item(i);
                int begin = millis(attribute(line, "begin"));
                if (begin < 0) continue;
                String key = attribute(line, "key");
                if (key.isEmpty()) key = attribute(line, "id");
                int end = millis(attribute(line, "end"));
                String agent = line.getAttributeNS("http://www.w3.org/ns/ttml#metadata", "agent");
                if (agent.matches("[A-Za-z0-9_.-]{1,48}") && !voices.containsKey(agent) && voices.size() < 2) {
                    voices.put(agent, voices.size() + 1);
                }
                StringBuilder text = new StringBuilder();
                List<Lyrics.Word> words = new ArrayList<>();
                appendText(line, text, words);
                String trimmed = text.toString().trim();
                int lead = text.indexOf(trimmed);
                List<Lyrics.Word> aligned = new ArrayList<>();
                for (Lyrics.Word word : words) {
                    int from = word.from - lead;
                    int to = word.to - lead;
                    if (from >= 0 && to <= trimmed.length() && word.start >= begin
                            && (end < begin || word.end <= end)
                            && (aligned.isEmpty() || aligned.get(aligned.size() - 1).end <= word.start)) {
                        aligned.add(new Lyrics.Word(word.start, word.end, from, to));
                    }
                }
                lines.add(new Lyrics.Line(begin, end > begin ? end : -1, trimmed, roman.get(key), translations.get(key),
                        aligned, voices.getOrDefault(agent, 0)));
            }
        }
        lines.sort(Comparator.comparingInt(line -> line.millis));
        return new Lyrics(lines, "", "Unison", attribute(root, "lang"));
    }

    private static void appendText(Element element, StringBuilder text, List<Lyrics.Word> words) {
        int from = text.length();
        int count = words.size();
        for (org.w3c.dom.Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element) appendText((Element) child, text, words);
            else if (child.getNodeType() == org.w3c.dom.Node.TEXT_NODE
                    || child.getNodeType() == org.w3c.dom.Node.CDATA_SECTION_NODE) text.append(child.getNodeValue());
        }
        if (!"span".equals(element.getLocalName()) || words.size() != count || count >= 256 || text.length() <= from) return;
        int start = millis(attribute(element, "begin"));
        int end = millis(attribute(element, "end"));
        if (start >= 0 && end > start) words.add(new Lyrics.Word(start, end, from, text.length()));
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
            if (time.matches("[0-9]+(?:\\.[0-9]+)?")) seconds = Double.parseDouble(time);
            else if (time.endsWith("s")) seconds = Double.parseDouble(time.substring(0, time.length() - 1));
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
