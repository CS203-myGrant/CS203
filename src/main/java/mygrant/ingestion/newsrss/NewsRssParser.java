package mygrant.ingestion.newsrss;

import java.io.StringReader;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/** Reads RSS 2.0 summaries; malformed items are skipped, malformed feeds fail. */
@Component
public class NewsRssParser {
    public ParsedFeed parse(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new IllegalArgumentException("RSS response is empty");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override
                public void fatalError(SAXParseException exception) throws SAXParseException {
                    throw exception;
                }
            });
            Element root = builder.parse(new InputSource(new StringReader(xml))).getDocumentElement();
            if (!"rss".equals(root.getTagName())) {
                throw new IllegalArgumentException("Response is not an RSS feed");
            }
            NodeList channels = root.getElementsByTagName("channel");
            if (channels.getLength() != 1) {
                throw new IllegalArgumentException("RSS feed must contain one channel");
            }
            NodeList items = ((Element) channels.item(0)).getElementsByTagName("item");
            List<Article> articles = new ArrayList<>();
            int skipped = 0;
            for (int i = 0; i < items.getLength(); i++) {
                try {
                    articles.add(parseItem((Element) items.item(i)));
                } catch (IllegalArgumentException exception) {
                    skipped++;
                }
            }
            if (items.getLength() > 0 && articles.isEmpty()) {
                throw new IllegalArgumentException("RSS feed contains no valid articles");
            }
            return new ParsedFeed(List.copyOf(articles), skipped);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Malformed RSS response", exception);
        }
    }

    private Article parseItem(Element item) {
        String title = field(item, "title").trim();
        String link = field(item, "link").trim();
        String description = org.jsoup.Jsoup.parse(field(item, "description")).text().trim();
        String published = field(item, "pubDate").trim();
        if (title.isBlank() || description.isBlank() || published.isBlank()
                || title.length() > 1000 || link.length() > 2048) {
            throw new IllegalArgumentException("RSS item has missing or oversized fields");
        }
        URI uri;
        try {
            uri = URI.create(link);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid RSS article URL", exception);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !"www.theguardian.com".equalsIgnoreCase(uri.getHost())
                || uri.getUserInfo() != null || uri.getPort() != -1
                || !uri.getPath().startsWith("/")) {
            throw new IllegalArgumentException("RSS article is outside selected publisher");
        }
        LocalDate date;
        try {
            date = ZonedDateTime.parse(published, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toLocalDate();
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Invalid RSS publication date", exception);
        }
        String canonicalUrl = "https://www.theguardian.com" + uri.getRawPath();
        return new Article(title, canonicalUrl, date, description);
    }

    private String field(Element parent, String name) {
        NodeList nodes = parent.getElementsByTagName(name);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent();
    }

    public record Article(String title, String url, LocalDate publicationDate, String summary) {}
    public record ParsedFeed(List<Article> articles, int invalidItems) {}
}
