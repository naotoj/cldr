package org.unicode.cldr.util;

import com.google.common.base.Joiner;
import com.ibm.icu.impl.Utility;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Transforms a path by replacing attributes with .*
 *
 * @author markdavis
 */
public class PathStarrer {
    public static final String STAR_PATTERN = "([^\"]*+)";

    private final List<String> attributes = new ArrayList<>();
    private String substitutionPattern = STAR_PATTERN;

    private static final Pattern ATTRIBUTE_PATTERN_OLD = PatternCache.get("=\"([^\"]*)\"");

    private static final ConcurrentHashMap<String, String> STAR_CACHE = new ConcurrentHashMap<>();

    /** Thread-safe version, only STAR_PATTERN */
    public static String get(String path) {
        return STAR_CACHE.computeIfAbsent(
                path,
                x -> {
                    XPathParts parts = XPathParts.getFrozenInstance(x).cloneAsThawed();
                    for (int i = 0; i < parts.size(); ++i) {
                        for (String key : parts.getAttributeKeys(i)) {
                            parts.setAttribute(i, key, STAR_PATTERN);
                        }
                    }
                    return parts.toString();
                });
    }

    public String set(String path) {
        XPathParts parts = XPathParts.getFrozenInstance(path).cloneAsThawed();
        attributes.clear();
        for (int i = 0; i < parts.size(); ++i) {
            for (String key : parts.getAttributeKeys(i)) {
                attributes.add(parts.getAttributeValue(i, key));
                parts.setAttribute(i, key, substitutionPattern);
            }
        }
        return parts.toString();
    }

    /**
     * Sets the path starrer attributes, and returns the string.
     *
     * @param parts
     * @return
     */
    public String setSkippingAttributes(XPathParts parts, Set<String> skipAttributes) {
        attributes.clear();
        for (int i = 0; i < parts.size(); ++i) {
            for (String key : parts.getAttributeKeys(i)) {
                if (!skipAttributes.contains(key)) {
                    attributes.add(parts.getAttributeValue(i, key));
                    parts.setAttribute(i, key, substitutionPattern);
                }
            }
        }
        return parts.toString();
    }

    public String setOld(String path) {
        Matcher starAttributeMatcher = ATTRIBUTE_PATTERN_OLD.matcher(path);
        StringBuilder starredPathOld = new StringBuilder();
        attributes.clear();
        int lastEnd = 0;
        while (starAttributeMatcher.find()) {
            int start = starAttributeMatcher.start(1);
            int end = starAttributeMatcher.end(1);
            starredPathOld.append(path, lastEnd, start);
            starredPathOld.append(substitutionPattern);

            attributes.add(path.substring(start, end));
            lastEnd = end;
        }
        starredPathOld.append(path.substring(lastEnd));
        return starredPathOld.toString();
    }

    public String getAttributesString(String separator) {
        return Joiner.on(separator).join(attributes);
    }

    public PathStarrer setSubstitutionPattern(String substitutionPattern) {
        this.substitutionPattern = substitutionPattern;
        return this;
    }

    // Used for coverage lookups - strips off the leading ^ and trailing $ from regexp pattern.
    public String transform2(String source) {
        String result = Utility.unescape(setOld(source));
        if (result.startsWith("^") && result.endsWith("$")) {
            result = result.substring(1, result.length() - 1);
        }
        return result;
    }
}
