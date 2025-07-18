package org.unicode.cldr.util;

import com.google.common.base.Splitter;
import com.ibm.icu.impl.Relation;
import com.ibm.icu.impl.Row;
import com.ibm.icu.impl.UnicodeMap;
import com.ibm.icu.lang.UCharacter;
import com.ibm.icu.text.Collator;
import com.ibm.icu.text.Transform;
import com.ibm.icu.text.UnicodeSet;
import com.ibm.icu.util.ICUException;
import com.ibm.icu.util.Output;
import com.ibm.icu.util.ULocale;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.unicode.cldr.draft.ScriptMetadata;
import org.unicode.cldr.draft.ScriptMetadata.Info;
import org.unicode.cldr.tool.LikelySubtags;
import org.unicode.cldr.util.RegexLookup.Finder;
import org.unicode.cldr.util.SupplementalDataInfo.PluralInfo.Count;
import org.unicode.cldr.util.With.SimpleIterator;
import org.unicode.cldr.util.personname.PersonNameFormatter;

/**
 * Provides a mechanism for dividing up LDML paths into understandable categories, eg for the Survey
 * tool.
 */
public class PathHeader implements Comparable<PathHeader> {
    /** Link to a section. Commenting out the page switch for now. */
    public static final String SECTION_LINK = "<a " + /* "target='CLDR_ST-SECTION' "+*/ "href='";

    static boolean UNIFORM_CONTINENTS = true;
    static Factory factorySingleton = null;

    static final boolean SKIP_ORIGINAL_PATH = true;

    private static final Logger logger = Logger.getLogger(PathHeader.class.getName());

    static final Splitter HYPHEN_SPLITTER = Splitter.on('-');

    public enum Width {
        FULL,
        LONG,
        WIDE,
        SHORT,
        NARROW;

        public static Width getValue(String input) {
            try {
                return Width.valueOf(input.toUpperCase(Locale.ENGLISH));
            } catch (RuntimeException e) {
                e.printStackTrace();
                throw e;
            }
        }

        @Override
        public String toString() {
            return name().toLowerCase(Locale.ENGLISH);
        }
    }

    /** What status the survey tool should use. Can be overridden in Phase.getAction() */
    public enum SurveyToolStatus {
        /** Never show. */
        DEPRECATED,
        /** Hide. Can be overridden in Phase.getAction() */
        HIDE,
        /**
         * Don't allow Change box (except TC), instead show ticket. But allow votes. Can be
         * overridden in Phase.getAction()
         */
        READ_ONLY,
        /** Allow change box and votes. Can be overridden in Phase.getAction() */
        READ_WRITE,
        /**
         * Changes are allowed as READ_WRITE, but field is always displayed as LTR, even in RTL
         * locales (used for patterns).
         */
        LTR_ALWAYS;

        /**
         * @returns true if visible in surveytool
         */
        public final boolean visible() {
            return (this != DEPRECATED && this != HIDE);
        }
    }

    private static final EnumNames<SectionId> SectionIdNames = new EnumNames<>();

    /**
     * The Section for a path. Don't change these without committee buy-in. The 'name' may be
     * 'Core_Data' and the toString is 'Core Data' toString gives the human name
     */
    public enum SectionId {
        Core_Data("Core Data"),
        Locale_Display_Names("Locale Display Names"),
        DateTime("Date & Time"),
        Timezones,
        Numbers,
        Currencies,
        Units,
        Characters,
        Misc("Miscellaneous"),
        BCP47,
        Supplemental,
        Special;

        SectionId(String... alternateNames) {
            SectionIdNames.add(this, alternateNames);
        }

        public static SectionId forString(String name) {
            return SectionIdNames.forString(name);
        }

        @Override
        public String toString() {
            return SectionIdNames.toString(this);
        }
    }

    private static final EnumNames<PageId> PageIdNames = new EnumNames<>();
    private static final Relation<SectionId, PageId> SectionIdToPageIds =
            Relation.of(new TreeMap<>(), TreeSet.class);

    private static class SubstringOrder implements Comparable<SubstringOrder> {
        final String mainOrder;
        final int order;

        public SubstringOrder(String source) {
            int pos = source.lastIndexOf('-') + 1;
            int ordering = COUNTS.indexOf(source.substring(pos));
            // account for digits, and "some" future proofing.
            order = ordering < 0 ? source.charAt(pos) : 0x10000 + ordering;
            mainOrder = source.substring(0, pos);
        }

        @Override
        public String toString() {
            return "{" + mainOrder + ", " + order + "}";
        }

        @Override
        public int compareTo(SubstringOrder other) {
            int diff = alphabeticCompare(mainOrder, other.mainOrder);
            if (diff != 0) {
                return diff;
            }
            return order - other.order;
        }
    }

    /**
     * The Page for a path (within a Section). Don't change these without committee buy-in. the name
     * is for example WAsia where toString gives Western Asia
     */
    public enum PageId {
        Alphabetic_Information(SectionId.Core_Data, "Alphabetic Information"),
        Numbering_Systems(SectionId.Core_Data, "Numbering Systems"),
        LinguisticElements(SectionId.Core_Data, "Linguistic Elements"),

        Locale_Name_Patterns(SectionId.Locale_Display_Names, "Locale Name Patterns"),
        Languages_A_D(SectionId.Locale_Display_Names, "Languages (A-D)"),
        Languages_E_J(SectionId.Locale_Display_Names, "Languages (E-J)"),
        Languages_K_N(SectionId.Locale_Display_Names, "Languages (K-N)"),
        Languages_O_S(SectionId.Locale_Display_Names, "Languages (O-S)"),
        Languages_T_Z(SectionId.Locale_Display_Names, "Languages (T-Z)"),
        Scripts(SectionId.Locale_Display_Names),
        Territories(SectionId.Locale_Display_Names, "Geographic Regions"),
        T_NAmerica(SectionId.Locale_Display_Names, "Territories (North America)"),
        T_SAmerica(SectionId.Locale_Display_Names, "Territories (South America)"),
        T_Africa(SectionId.Locale_Display_Names, "Territories (Africa)"),
        T_Europe(SectionId.Locale_Display_Names, "Territories (Europe)"),
        T_Asia(SectionId.Locale_Display_Names, "Territories (Asia)"),
        T_Oceania(SectionId.Locale_Display_Names, "Territories (Oceania)"),
        Locale_Variants(SectionId.Locale_Display_Names, "Locale Variants"),
        Keys(SectionId.Locale_Display_Names),

        Fields(SectionId.DateTime),
        Relative(SectionId.DateTime),
        Gregorian(SectionId.DateTime),
        Gregorian_YMD(SectionId.DateTime, "Gregorian YMD"),
        Generic(SectionId.DateTime),
        Buddhist(SectionId.DateTime),
        Chinese(SectionId.DateTime),
        Coptic(SectionId.DateTime),
        Dangi(SectionId.DateTime),
        Ethiopic(SectionId.DateTime),
        Ethiopic_Amete_Alem(SectionId.DateTime, "Ethiopic-Amete-Alem"),
        Hebrew(SectionId.DateTime),
        Indian(SectionId.DateTime),
        Islamic(SectionId.DateTime),
        Japanese(SectionId.DateTime),
        Persian(SectionId.DateTime),
        Minguo(SectionId.DateTime),

        Timezone_Display_Patterns(SectionId.Timezones, "Timezone Display Patterns"),
        NAmerica(SectionId.Timezones, "North America"),
        SAmerica(SectionId.Timezones, "South America"),
        Africa(SectionId.Timezones),
        Europe(SectionId.Timezones),
        Russia(SectionId.Timezones),
        WAsia(SectionId.Timezones, "Western Asia"),
        CAsia(SectionId.Timezones, "Central Asia"),
        EAsia(SectionId.Timezones, "Eastern Asia"),
        SAsia(SectionId.Timezones, "Southern Asia"),
        SEAsia(SectionId.Timezones, "Southeast Asia"),
        Australasia(SectionId.Timezones),
        Antarctica(SectionId.Timezones),
        Oceania(SectionId.Timezones),
        UnknownT(SectionId.Timezones, "Unknown Region"),
        Overrides(SectionId.Timezones),

        Symbols(SectionId.Numbers),
        Number_Formatting_Patterns(SectionId.Numbers, "Number Formatting Patterns"),
        Compact_Decimal_Formatting(SectionId.Numbers, "Compact Decimal Formatting"),
        Compact_Decimal_Formatting_Other(
                SectionId.Numbers, "Compact Decimal Formatting (Other Numbering Systems)"),

        Measurement_Systems(SectionId.Units, "Measurement Systems"),
        Duration(SectionId.Units),
        Graphics(SectionId.Units),
        Length_Metric(SectionId.Units, "Length Metric"),
        Length_Other(SectionId.Units, "Length Other"),
        Area(SectionId.Units),
        Volume_Metric(SectionId.Units, "Volume Metric"),
        Volume_US(SectionId.Units, "Volume US"),
        Volume_Other(SectionId.Units, "Volume Other"),
        SpeedAcceleration(SectionId.Units, "Speed and Acceleration"),
        MassWeight(SectionId.Units, "Mass and Weight"),
        EnergyPower(SectionId.Units, "Energy and Power"),
        ElectricalFrequency(SectionId.Units, "Electrical and Frequency"),
        Weather(SectionId.Units),
        Digital(SectionId.Units),
        Coordinates(SectionId.Units),
        OtherUnitsMetric(SectionId.Units, "Other Units Metric"),
        OtherUnitsMetricPer(SectionId.Units, "Other Units Metric Per"),
        OtherUnitsUS(SectionId.Units, "Other Units US"),
        OtherUnits(SectionId.Units, "Other Units"),
        CompoundUnits(SectionId.Units, "Compound Units"),

        Displaying_Lists(SectionId.Misc, "Displaying Lists"),
        MinimalPairs(SectionId.Misc, "Minimal Pairs"),
        PersonNameFormats(SectionId.Misc, "Person Name Formats"),
        Transforms(SectionId.Misc),

        Identity(SectionId.Special),
        Version(SectionId.Special),
        Suppress(SectionId.Special),
        Deprecated(SectionId.Special),
        Unknown(SectionId.Special),

        C_NAmerica(SectionId.Currencies, "North America (C)"),
        // need to add (C) to differentiate from Timezone territories
        C_SAmerica(SectionId.Currencies, "South America (C)"),
        C_NWEurope(SectionId.Currencies, "Northern/Western Europe"),
        C_SEEurope(SectionId.Currencies, "Southern/Eastern Europe"),
        C_NAfrica(SectionId.Currencies, "Northern Africa"),
        C_WAfrica(SectionId.Currencies, "Western Africa"),
        C_MAfrica(SectionId.Currencies, "Middle Africa"),
        C_EAfrica(SectionId.Currencies, "Eastern Africa"),
        C_SAfrica(SectionId.Currencies, "Southern Africa"),
        C_WAsia(SectionId.Currencies, "Western Asia (C)"),
        C_CAsia(SectionId.Currencies, "Central Asia (C)"),
        C_EAsia(SectionId.Currencies, "Eastern Asia (C)"),
        C_SAsia(SectionId.Currencies, "Southern Asia (C)"),
        C_SEAsia(SectionId.Currencies, "Southeast Asia (C)"),
        C_Oceania(SectionId.Currencies, "Oceania (C)"),
        C_Unknown(SectionId.Currencies, "Unknown Region (C)"),

        // BCP47
        u_Extension(SectionId.BCP47),
        t_Extension(SectionId.BCP47),

        // Supplemental
        Alias(SectionId.Supplemental),
        IdValidity(SectionId.Supplemental),
        Locale(SectionId.Supplemental),
        RegionMapping(SectionId.Supplemental),
        WZoneMapping(SectionId.Supplemental),
        Transform(SectionId.Supplemental),
        Units(SectionId.Supplemental),
        Likely(SectionId.Supplemental),
        LanguageMatch(SectionId.Supplemental),
        TerritoryInfo(SectionId.Supplemental),
        LanguageInfo(SectionId.Supplemental),
        LanguageGroup(SectionId.Supplemental),
        Fallback(SectionId.Supplemental),
        Gender(SectionId.Supplemental),
        Grammar(SectionId.Supplemental),
        Metazone(SectionId.Supplemental),
        NumberSystem(SectionId.Supplemental),
        Plural(SectionId.Supplemental),
        PluralRange(SectionId.Supplemental),
        Containment(SectionId.Supplemental),
        Currency(SectionId.Supplemental),
        Calendar(SectionId.Supplemental),
        WeekData(SectionId.Supplemental),
        Measurement(SectionId.Supplemental),
        Language(SectionId.Supplemental),
        Script(SectionId.Supplemental),
        RBNF(SectionId.Supplemental),
        Segmentation(SectionId.Supplemental),
        DayPeriod(SectionId.Supplemental),

        Category(SectionId.Characters),

        // [Smileys, People, Animals & Nature, Food & Drink, Travel & Places, Activities, Objects,
        // Symbols, Flags]
        Smileys(SectionId.Characters, "Smileys & Emotion"),
        People(SectionId.Characters, "People & Body"),
        People2(SectionId.Characters, "People & Body 2"),
        Animals_Nature(SectionId.Characters, "Animals & Nature"),
        Food_Drink(SectionId.Characters, "Food & Drink"),
        Travel_Places(SectionId.Characters, "Travel & Places"),
        Travel_Places2(SectionId.Characters, "Travel & Places 2"),
        Activities(SectionId.Characters),
        Objects(SectionId.Characters),
        Objects2(SectionId.Characters),
        EmojiSymbols(SectionId.Characters, "Emoji Symbols"),
        Punctuation(SectionId.Characters),
        MathSymbols(SectionId.Characters, "Math Symbols"),
        OtherSymbols(SectionId.Characters, "Other Symbols"),
        Flags(SectionId.Characters),
        Component(SectionId.Characters),
        Typography(SectionId.Characters),
        ;

        private final SectionId sectionId;

        PageId(SectionId sectionId, String... alternateNames) {
            this.sectionId = sectionId;
            SectionIdToPageIds.put(sectionId, this);
            PageIdNames.add(this, alternateNames);
        }

        /**
         * Construct a pageId given a string
         *
         * @param name the name of a page, such as "Alphabetic Information"
         * @return the PageId, or null
         */
        public static PageId fromString(String name) {
            try {
                return PageIdNames.forString(name);
            } catch (Exception e) {
                return null;
            }
        }

        /**
         * Construct a pageId given a string
         *
         * @param name the name of a page, such as "Alphabetic Information"
         * @return the PageId
         * @throws ICUException if the name is not recognized
         */
        public static PageId forString(String name) {
            try {
                return PageIdNames.forString(name);
            } catch (Exception e) {
                throw new ICUException("No PageId for " + name, e);
            }
        }

        /**
         * Returns the page id
         *
         * @return a page ID, such as 'Languages'
         */
        @Override
        public String toString() {
            return PageIdNames.toString(this);
        }

        /**
         * Get the containing section id, such as 'Code Lists'
         *
         * @return the containing section ID
         */
        public SectionId getSectionId() {
            return sectionId;
        }
    }

    private final SectionId sectionId;
    private final PageId pageId;
    private final String header;
    private final String code;
    private final String originalPath;
    private final SurveyToolStatus status;

    // Used for ordering
    private final int headerOrder;
    private final long codeOrder;
    private final SubstringOrder codeSuborder;

    static final Pattern SEMI = PatternCache.get("\\s*;\\s*");
    static final Matcher ALT_MATCHER = PatternCache.get("\\[@alt=\"([^\"]*+)\"]").matcher("");

    static final SupplementalDataInfo supplementalDataInfo = SupplementalDataInfo.getInstance();
    static final Map<String, String> metazoneToContinent =
            supplementalDataInfo.getMetazoneToContinentMap();
    static final Map<String, String> metazoneToPageTerritory = new HashMap<>();

    static {
        Map<String, Map<String, String>> metazoneToRegionToZone =
                supplementalDataInfo.getMetazoneToRegionToZone();
        for (Entry<String, Map<String, String>> metazoneEntry : metazoneToRegionToZone.entrySet()) {
            String metazone = metazoneEntry.getKey();
            String worldZone = metazoneEntry.getValue().get("001");
            String territory = Containment.getRegionFromZone(worldZone);
            if (territory == null) {
                territory = "ZZ";
            }
            // Russia, Antarctica => territory
            // in Australasia, Asia, S. America => subcontinent
            // in N. America => N. America (grouping of 3 subcontinents)
            // in everything else => continent
            if (territory.equals("RU") || territory.equals("AQ")) {
                metazoneToPageTerritory.put(metazone, territory);
            } else {
                String continent = Containment.getContinent(territory);
                String subcontinent = Containment.getSubcontinent(territory);
                if (continent.equals("142")) { // Asia
                    metazoneToPageTerritory.put(metazone, subcontinent);
                } else if (continent.equals("019")) { // Americas
                    metazoneToPageTerritory.put(
                            metazone, subcontinent.equals("005") ? subcontinent : "003");
                } else if (subcontinent.equals("053")) { // Australasia
                    metazoneToPageTerritory.put(metazone, subcontinent);
                } else {
                    metazoneToPageTerritory.put(metazone, continent);
                }
            }
        }
    }

    private PathHeader(
            SectionId sectionId,
            PageId pageId,
            String header,
            int headerOrder,
            String code,
            long codeOrder,
            SubstringOrder suborder,
            SurveyToolStatus status,
            String originalPath) {
        this.sectionId = sectionId;
        this.pageId = pageId;
        this.header = header;
        this.headerOrder = headerOrder;
        this.code = code;
        this.codeOrder = codeOrder;
        this.codeSuborder = suborder;
        this.originalPath = originalPath;
        this.status = status;
    }

    /**
     * Return a factory for use in creating the headers. This is cached after first use. The calls
     * are thread-safe. Null gets the default (CLDRConfig) english file.
     *
     * @param englishFile
     */
    public static Factory getFactory(CLDRFile englishFile) {
        if (factorySingleton == null) {
            if (englishFile == null) {
                englishFile = CLDRConfig.getInstance().getEnglish();
            }
            if (!englishFile.getLocaleID().equals(ULocale.ENGLISH.getBaseName())) {
                throw new IllegalArgumentException(
                        "PathHeader's CLDRFile must be '"
                                + ULocale.ENGLISH.getBaseName()
                                + "', but found '"
                                + englishFile.getLocaleID()
                                + "'");
            }
            factorySingleton = new Factory(englishFile);
        }
        return factorySingleton;
    }

    /** Convenience method for common case. See {{@link #getFactory(CLDRFile)}} */
    public static Factory getFactory() {
        return getFactory(null);
    }

    /**
     * @deprecated
     */
    @Deprecated
    public String getSection() {
        return sectionId.toString();
    }

    public SectionId getSectionId() {
        return sectionId;
    }

    /**
     * @deprecated
     */
    @Deprecated
    public String getPage() {
        return pageId.toString();
    }

    public PageId getPageId() {
        return pageId;
    }

    public String getHeader() {
        return header == null ? "" : header;
    }

    public String getCode() {
        return code;
    }

    public String getHeaderCode() {
        return getHeader() + ": " + getCode();
    }

    public String getOriginalPath() {
        return originalPath;
    }

    public SurveyToolStatus getSurveyToolStatus() {
        return status;
    }

    @Override
    public String toString() {
        return sectionId
                + "\t"
                + pageId
                + "\t"
                + header // + "\t" + headerOrder
                + "\t"
                + code // + "\t" + codeOrder
        ;
    }

    /**
     * Compare this PathHeader to another one
     *
     * @param other the object to be compared.
     * @return 0 if equal, -1 if less, 1 if more
     *     <p>Note: if we ever have to compare just the header or just the code, methods to do that
     *     were in release 44 (compareHeader and compareCode), but they were unused and therefore
     *     removed in CLDR-11155.
     */
    @Override
    public int compareTo(PathHeader other) {
        // Within each section, order alphabetically if the integer orders are
        // not different.
        try {
            int result;
            if (0 != (result = sectionId.compareTo(other.sectionId))) {
                return result;
            }
            if (0 != (result = pageId.compareTo(other.pageId))) {
                return result;
            }
            if (0 != (result = headerOrder - other.headerOrder)) {
                return result;
            }
            if (0 != (result = alphabeticCompare(header, other.header))) {
                return result;
            }
            long longResult;
            if (0 != (longResult = codeOrder - other.codeOrder)) {
                return longResult < 0 ? -1 : 1;
            }
            if (codeSuborder != null) { // do all three cases, for transitivity
                if (other.codeSuborder != null) {
                    if (0 != (result = codeSuborder.compareTo(other.codeSuborder))) {
                        return result;
                    }
                } else {
                    return 1; // if codeSuborder != null (and other.codeSuborder
                    // == null), it is greater
                }
            } else if (other.codeSuborder != null) {
                return -1; // if codeSuborder == null (and other.codeSuborder !=
                // null), it is greater
            }
            if (0 != (result = alphabeticCompare(code, other.code))) {
                return result;
            }
            if (!SKIP_ORIGINAL_PATH) {
                if (0 != (result = alphabeticCompare(originalPath, other.originalPath))) {
                    return result;
                }
            }
            return 0;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "Internal problem comparing " + this + " and " + other, e);
        }
    }

    @Override
    public boolean equals(Object obj) {
        PathHeader other;
        try {
            other = (PathHeader) obj;
        } catch (Exception e) {
            return false;
        }
        return sectionId == other.sectionId
                && pageId == other.pageId
                && header.equals(other.header)
                && code.equals(other.code);
    }

    @Override
    public int hashCode() {
        return sectionId.hashCode() ^ pageId.hashCode() ^ header.hashCode() ^ code.hashCode();
    }

    public static class Factory implements Transform<String, PathHeader> {
        static final RegexLookup<RawData> lookup =
                RegexLookup.of(new PathHeaderTransform())
                        .setPatternTransform(RegexLookup.RegexFinderTransformPath)
                        .loadFromFile(PathHeader.class, "data/PathHeader.txt");
        // synchronized with lookup
        static final Output<String[]> args = new Output<>();
        // synchronized with lookup
        static final Counter<RawData> counter = new Counter<>();
        // synchronized with lookup
        static final Map<RawData, String> samples = new HashMap<>();
        // synchronized with lookup
        static long order;
        static SubstringOrder suborder;

        static final Map<String, PathHeader> cache = new HashMap<>();
        // synchronized with cache
        static final Map<SectionId, Map<PageId, SectionPage>> sectionToPageToSectionPage =
                new EnumMap<>(SectionId.class);
        static final Relation<SectionPage, String> sectionPageToPaths =
                Relation.of(new TreeMap<>(), HashSet.class);
        private static CLDRFile englishFile;
        private static NameGetter englishNameGetter;
        private final Set<String> matchersFound = new HashSet<>();

        /**
         * Create a factory for creating PathHeaders.
         *
         * @param englishFile - only sets the file (statically!) if not already set.
         */
        private Factory(CLDRFile englishFile) {
            setEnglishCLDRFileIfNotSet(englishFile); // temporary
        }

        /**
         * Set englishFile if it is not already set.
         *
         * @param englishFile2 the value to set for englishFile
         */
        private static void setEnglishCLDRFileIfNotSet(CLDRFile englishFile2) {
            synchronized (Factory.class) {
                if (englishFile == null) {
                    englishFile = englishFile2;
                    englishNameGetter = englishFile.nameGetter();
                }
            }
        }

        /** Use only when trying to find unmatched patterns */
        public void clearCache() {
            synchronized (cache) {
                cache.clear();
            }
        }

        /** Return the PathHeader for a given path. Thread-safe. */
        public PathHeader fromPath(String path) {
            return fromPath(path, null);
        }

        /** Return the PathHeader for a given path. Thread-safe. */
        @Override
        public PathHeader transform(String path) {
            return fromPath(path, null);
        }

        /**
         * Return the PathHeader for a given path. Thread-safe.
         *
         * @param failures a list of failures to add to.
         */
        public PathHeader fromPath(final String path, List<String> failures) {
            if (path == null) {
                throw new NullPointerException("Path cannot be null");
            }
            synchronized (cache) {
                PathHeader old = cache.get(path);
                if (old != null) {
                    return old;
                }
            }
            synchronized (lookup) {
                String cleanPath = path;
                // special handling for alt
                String alt = null;
                int altPos = cleanPath.indexOf("[@alt=");
                if (altPos >= 0 && !cleanPath.endsWith("/symbol[@alt=\"narrow\"]")) {
                    if (ALT_MATCHER.reset(cleanPath).find()) {
                        alt = ALT_MATCHER.group(1);
                        cleanPath =
                                cleanPath.substring(0, ALT_MATCHER.start())
                                        + cleanPath.substring(ALT_MATCHER.end());
                        int pos = alt.indexOf("proposed");
                        if (pos >= 0 && !path.startsWith("//ldml/collations")) {
                            alt = pos == 0 ? null : alt.substring(0, pos - 1);
                            // drop "proposed",
                            // change "xxx-proposed" to xxx.
                        }
                    } else {
                        throw new IllegalArgumentException();
                    }
                }
                Output<Finder> matcherFound = new Output<>();
                RawData data = lookup.get(cleanPath, null, args, matcherFound, failures);
                if (data == null) {
                    return null;
                }
                matchersFound.add(matcherFound.value.toString());
                counter.add(data, 1);
                if (!samples.containsKey(data)) {
                    samples.put(data, cleanPath);
                }
                try {
                    PathHeader result = makePathHeader(data, path, alt);
                    synchronized (cache) {
                        PathHeader old = cache.get(path);
                        if (old == null) {
                            cache.put(path, result);
                        } else {
                            result = old;
                        }
                        Map<PageId, SectionPage> pageToPathHeaders =
                                sectionToPageToSectionPage.get(result.sectionId);
                        if (pageToPathHeaders == null) {
                            sectionToPageToSectionPage.put(
                                    result.sectionId,
                                    pageToPathHeaders = new EnumMap<>(PageId.class));
                        }
                        SectionPage sectionPage = pageToPathHeaders.get(result.pageId);
                        if (sectionPage == null) {
                            sectionPage = new SectionPage(result.sectionId, result.pageId);
                            pageToPathHeaders.put(result.pageId, sectionPage);
                        }
                        sectionPageToPaths.put(sectionPage, path);
                    }
                    return result;
                } catch (Exception e) {
                    throw new IllegalArgumentException(
                            "Probably mismatch in Page/Section enum, or too few capturing groups in regex for "
                                    + path,
                            e);
                }
            }
        }

        private PathHeader makePathHeader(RawData data, String path, String alt) {
            // Caution: each call to PathHeader.Factory.fix changes the value of
            // PathHeader.Factory.order
            SectionId newSectionId = SectionId.forString(fix(data.section, 0));
            PageId newPageId = PageId.forString(fix(data.page, 0));
            String newHeader = fix(data.header, data.headerOrder);
            int newHeaderOrder = (int) order;
            String codeDashAlt = data.code + (alt == null ? "" : ("-" + alt));
            String newCode = fix(codeDashAlt, data.codeOrder);
            long newCodeOrder = order;
            return new PathHeader(
                    newSectionId,
                    newPageId,
                    newHeader,
                    newHeaderOrder,
                    newCode,
                    newCodeOrder,
                    suborder,
                    data.status,
                    path);
        }

        private static class SectionPage implements Comparable<SectionPage> {
            private final SectionId sectionId;
            private final PageId pageId;

            public SectionPage(SectionId sectionId, PageId pageId) {
                this.sectionId = sectionId;
                this.pageId = pageId;
            }

            @Override
            public int compareTo(SectionPage other) {
                // Within each section, order alphabetically if the integer
                // orders are
                // not different.
                int result;
                if (0 != (result = sectionId.compareTo(other.sectionId))) {
                    return result;
                }
                if (0 != (result = pageId.compareTo(other.pageId))) {
                    return result;
                }
                return 0;
            }

            @Override
            public boolean equals(Object obj) {
                PathHeader other;
                try {
                    other = (PathHeader) obj;
                } catch (Exception e) {
                    return false;
                }
                return sectionId == other.sectionId && pageId == other.pageId;
            }

            @Override
            public int hashCode() {
                return sectionId.hashCode() ^ pageId.hashCode();
            }

            @Override
            public String toString() {
                return sectionId + " > " + pageId;
            }
        }

        /**
         * Returns a set of paths currently associated with the given section and page.
         *
         * <p><b>Warning:</b>
         *
         * <ol>
         *   <li>The set may not be complete for a cldrFile unless all of paths in the file have had
         *       fromPath called. And this includes getExtraPaths().
         *   <li>The set may include paths that have no value in the current cldrFile.
         *   <li>The set may be empty, if the section/page aren't valid.
         * </ol>
         *
         * Thread-safe.
         */
        public static Set<String> getCachedPaths(SectionId sectionId, PageId page) {
            Set<String> target = new HashSet<>();
            synchronized (cache) {
                Map<PageId, SectionPage> pageToSectionPage =
                        sectionToPageToSectionPage.get(sectionId);
                if (pageToSectionPage == null) {
                    return target;
                }
                SectionPage sectionPage = pageToSectionPage.get(page);
                if (sectionPage == null) {
                    return target;
                }
                Set<String> set = sectionPageToPaths.getAll(sectionPage);
                target.addAll(set);
            }
            return target;
        }

        /**
         * Return the Sections and Pages that are in defined, for display in menus. Both are
         * ordered.
         */
        public static Relation<SectionId, PageId> getSectionIdsToPageIds() {
            SectionIdToPageIds.freeze(); // just in case
            return SectionIdToPageIds;
        }

        public Iterable<String> filterCldr(SectionId section, PageId page, CLDRFile file) {
            return new FilteredIterable(section, page, file);
        }

        private class FilteredIterable implements Iterable<String>, SimpleIterator<String> {
            private final SectionId sectionId;
            private final PageId pageId;
            private final Iterator<String> fileIterator;

            FilteredIterable(SectionId sectionId, PageId pageId, CLDRFile file) {
                this.sectionId = sectionId;
                this.pageId = pageId;
                this.fileIterator = file.fullIterable().iterator();
            }

            public FilteredIterable(String section, String page, CLDRFile file) {
                this(SectionId.forString(section), PageId.forString(page), file);
            }

            @Override
            public Iterator<String> iterator() {
                return With.toIterator(this);
            }

            @Override
            public String next() {
                while (fileIterator.hasNext()) {
                    String path = fileIterator.next();
                    PathHeader pathHeader = fromPath(path);
                    if (sectionId == pathHeader.sectionId && pageId == pathHeader.pageId) {
                        return path;
                    }
                }
                return null;
            }
        }

        private static class ChronologicalOrder {
            private final Map<String, Integer> map = new HashMap<>();
            private String item;
            private int order;
            private final ChronologicalOrder toClear;

            ChronologicalOrder(ChronologicalOrder toClear) {
                this.toClear = toClear;
            }

            int getOrder() {
                return order;
            }

            public String set(String itemToOrder) {
                if (itemToOrder.startsWith("*")) {
                    item = itemToOrder.substring(1, itemToOrder.length());
                    return item; // keep old order
                }
                item = itemToOrder;
                Integer old = map.get(item);
                if (old != null) {
                    order = old.intValue();
                } else {
                    order = map.size();
                    map.put(item, order);
                    clearLower();
                }
                return item;
            }

            private void clearLower() {
                if (toClear != null) {
                    toClear.map.clear();
                    toClear.order = 0;
                    toClear.clearLower();
                }
            }
        }

        static class RawData {
            static ChronologicalOrder codeOrdering = new ChronologicalOrder(null);
            static ChronologicalOrder headerOrdering = new ChronologicalOrder(codeOrdering);

            public RawData(String source) {
                String[] split = SEMI.split(source);
                section = split[0];
                // HACK
                if (section.equals("Timezones") && split[1].equals("Indian")) {
                    page = "Indian2";
                } else {
                    page = split[1];
                }

                header = headerOrdering.set(split[2]);
                headerOrder = headerOrdering.getOrder();

                code = codeOrdering.set(split[3]);
                codeOrder = codeOrdering.getOrder();

                status =
                        split.length < 5
                                ? SurveyToolStatus.READ_WRITE
                                : SurveyToolStatus.valueOf(split[4]);
            }

            public final String section;
            public final String page;
            public final String header;
            public final int headerOrder;
            public final String code;
            public final int codeOrder;
            public final SurveyToolStatus status;

            @Override
            public String toString() {
                return section
                        + "\t"
                        + page
                        + "\t"
                        + header
                        + "\t"
                        + headerOrder
                        + "\t"
                        + code
                        + "\t"
                        + codeOrder
                        + "\t"
                        + status;
            }
        }

        static class PathHeaderTransform implements Transform<String, RawData> {
            @Override
            public RawData transform(String source) {
                return new RawData(source);
            }
        }

        /**
         * Internal data, for testing and debugging.
         *
         * @deprecated
         */
        @Deprecated
        public class CounterData extends Row.R4<String, RawData, String, String> {
            public CounterData(String a, RawData b, String c) {
                super(
                        a,
                        b,
                        c == null ? "no sample" : c,
                        c == null ? "no sample" : fromPath(c).toString());
            }
        }

        /**
         * Get the internal data, for testing and debugging.
         *
         * @deprecated
         */
        @Deprecated
        public Counter<CounterData> getInternalCounter() {
            synchronized (lookup) {
                Counter<CounterData> result = new Counter<>();
                for (Map.Entry<Finder, RawData> foo : lookup) {
                    Finder finder = foo.getKey();
                    RawData data = foo.getValue();
                    long count = counter.get(data);
                    result.add(new CounterData(finder.toString(), data, samples.get(data)), count);
                }
                return result;
            }
        }

        static Map<String, Transform<String, String>> functionMap = new HashMap<>();
        static String[] months = {
            "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
            "Und"
        };
        static List<String> days = Arrays.asList("sun", "mon", "tue", "wed", "thu", "fri", "sat");
        static List<String> unitOrder = DtdData.getUnitOrder().getOrder();
        static final MapComparator<String> dayPeriods =
                new MapComparator<String>()
                        .add(
                                "am",
                                "pm",
                                "midnight",
                                "noon",
                                "morning1",
                                "morning2",
                                "afternoon1",
                                "afternoon2",
                                "evening1",
                                "evening2",
                                "night1",
                                "night2")
                        .freeze();
        static LikelySubtags likelySubtags = new LikelySubtags();
        static HyphenSplitter hyphenSplitter = new HyphenSplitter();
        static Transform<String, String> catFromTerritory;
        static Transform<String, String> catFromTimezone;

        static {
            // Put any new functions used in PathHeader.txt in here.
            // To change the order of items within a section or heading, set
            // order/suborder to be the relative position of the current item.
            functionMap.put(
                    "month",
                    new Transform<>() {
                        @Override
                        public String transform(String source) {
                            int m = Integer.parseInt(source);
                            order = m;
                            return months[m - 1];
                        }
                    });
            functionMap.put(
                    "count",
                    new Transform<>() {
                        @Override
                        public String transform(String source) {
                            suborder = new SubstringOrder(source);
                            return source;
                        }
                    });
            functionMap.put(
                    "count2",
                    new Transform<>() {
                        @Override
                        public String transform(String source) {
                            int pos = source.indexOf('-');
                            source = pos + source.substring(pos);
                            suborder = new SubstringOrder(source); // make 10000-...
                            // into 5-
                            return source;
                        }
                    });
            functionMap.put(
                    "currencySymbol",
                    new Transform<>() {
                        @Override
                        public String transform(String source) {
                            order = 901;
                            if (source.endsWith("narrow")) {
                                order = 902;
                            }
                            if (source.endsWith("variant")) {
                                order = 903;
                            }
                            return source;
                        }
                    });
            // &unitCount($1-$3-$4), where $1 is length, $2 is count, $3 is case (optional)
            // but also
            // &unitCount($1-$3-$5-$4), where $5 is case, $4 is gender — notice order change
            functionMap.put(
                    "unitCount",
                    new Transform<>() {
                        @Override
                        public String transform(String source) {
                            List<String> parts = HYPHEN_SPLITTER.splitToList(source);
                            if (parts.size() == 1) {
                                return source;
                            }
                            int lengthNumber = Width.getValue(parts.get(0)).ordinal();
                            int type = 0;
                            int rest = 0;
                            switch (parts.get(1)) {
                                case "gender":
                                    type = 0;
                                    break;
                                case "displayName":
                                    type = 1;
                                    break;
                                case "per":
                                    type = 2;
                                    break;
                                default:
                                    type = 3;
                                    int countNumber =
                                            (parts.size() > 1
                                                            ? Count.valueOf(parts.get(1))
                                                            : Count.other)
                                                    .ordinal();
                                    int caseNumber =
                                            (parts.size() > 2
                                                            ? GrammarInfo.CaseValues.valueOf(
                                                                    parts.get(2))
                                                            : GrammarInfo.CaseValues.nominative)
                                                    .ordinal();
                                    int genderNumber = GrammarInfo.GenderValues.neuter.ordinal();
                                    if (parts.size() > 3) {
                                        String genderPart = parts.get(3);
                                        if (!genderPart.equals("dgender")) {
                                            genderNumber =
                                                    GrammarInfo.GenderValues.valueOf(genderPart)
                                                            .ordinal();
                                        }
                                        type = 4;
                                    }
                                    rest = (countNumber << 16) | (caseNumber << 8) | genderNumber;
                                    break;
                            }
                            order = (type << 28) | (lengthNumber << 24) | rest;
                            return source;
                        }
                    });

            functionMap.put(
                    "pluralNumber",
                    new Transform<>() {
                        @Override
                        public String transform(String source) {
                            order = GrammarInfo.PluralValues.valueOf(source).ordinal();
                            return source;
                        }
                    });

            functionMap.put(
                    "caseNumber",
                    new Transform<>() {
                        @Override
                        public String transform(String source) {
                            order = GrammarInfo.CaseValues.valueOf(source).ordinal();
                            return source;
                        }
                    });

            functionMap.put(
                    "genderNumber",
                    new Transform<>() {
                        @Override
                        public String transform(String source) {
                            order = GrammarInfo.GenderValues.valueOf(source).ordinal();
                            return source;
                        }
                    });

            functionMap.put(
                    "day",
                    new Transform<>() {
                        @Override
                        public String transform(String source) {
                            int m = days.indexOf(source);
                            order = m;
                            return source;
                        }
                    });
            functionMap.put(
                    "dayPeriod",
                    new Transform<>() {
                        @Override
                        public String transform(String source) {
                            try {
                                order = dayPeriods.getNumericOrder(source);
                            } catch (Exception e) {
                                // if an old item is tried, like "evening", this will fail.
                                // so that old data still works, hack this.
                                order = Math.abs(source.hashCode() << 16);
                            }
                            return source;
                        }
                    });
            functionMap.put(
                    "calendar",
                    new Transform<>() {
                        final Map<String, String> fixNames =
                                Builder.with(new HashMap<String, String>())
                                        .put("islamicc", "Islamic Civil")
                                        .put("roc", "Minguo")
                                        .put("Ethioaa", "Ethiopic Amete Alem")
                                        .put("Gregory", "Gregorian")
                                        .put("iso8601", "Gregorian YMD")
                                        .freeze();

                        @Override
                        public String transform(String source) {
                            String result = fixNames.get(source);
                            return result != null ? result : UCharacter.toTitleCase(source, null);
                        }
                    });

            functionMap.put(
                    "calField",
                    new Transform<>() {
                        @Override
                        public String transform(String source) {
                            String[] fields = source.split(":", 3);
                            order = 0;
                            final List<String> widthValues =
                                    Arrays.asList("wide", "abbreviated", "short", "narrow");
                            final List<String> calendarFieldValues =
                                    Arrays.asList(
                                            "Eras",
                                            "Quarters",
                                            "Months",
                                            "Days",
                                            "DayPeriods",
                                            "Formats");
                            final List<String> calendarFormatTypes =
                                    Arrays.asList("Standard", "Flexible", "Intervals");
                            final List<String> calendarContextTypes =
                                    Arrays.asList("none", "format", "stand-alone");
                            final List<String> calendarFormatSubtypes =
                                    Arrays.asList(
                                            "date",
                                            "time",
                                            "time12",
                                            "time24",
                                            "dateTime",
                                            "fallback");

                            Map<String, String> fixNames =
                                    Builder.with(new HashMap<String, String>())
                                            .put("DayPeriods", "Day Periods")
                                            .put("format", "Formatting")
                                            .put("stand-alone", "Standalone")
                                            .put("none", "")
                                            .put("date", "Date Formats")
                                            .put("time", "Time Formats")
                                            .put("time12", "12 Hour Time Formats")
                                            .put("time24", "24 Hour Time Formats")
                                            .put("dateTime", "Date & Time Combination Formats")
                                            .freeze();

                            if (calendarFieldValues.contains(fields[0])) {
                                order = calendarFieldValues.indexOf(fields[0]) * 100;
                            } else {
                                order = calendarFieldValues.size() * 100;
                            }

                            if (fields[0].equals("Formats")) {
                                if (calendarFormatTypes.contains(fields[1])) {
                                    order += calendarFormatTypes.indexOf(fields[1]) * 10;
                                } else {
                                    order += calendarFormatTypes.size() * 10;
                                }
                                if (calendarFormatSubtypes.contains(fields[2])) {
                                    order += calendarFormatSubtypes.indexOf(fields[2]);
                                } else {
                                    order += calendarFormatSubtypes.size();
                                }
                            } else {
                                if (widthValues.contains(fields[1])) {
                                    order += widthValues.indexOf(fields[1]) * 10;
                                } else {
                                    order += widthValues.size() * 10;
                                }
                                if (calendarContextTypes.contains(fields[2])) {
                                    order += calendarContextTypes.indexOf(fields[2]);
                                } else {
                                    order += calendarContextTypes.size();
                                }
                            }

                            String[] fixedFields = new String[fields.length];
                            for (int i = 0; i < fields.length; i++) {
                                String s = fixNames.get(fields[i]);
                                fixedFields[i] = s != null ? s : fields[i];
                            }

                            return fixedFields[0]
                                    + " - "
                                    + fixedFields[1]
                                    + (fixedFields[2].length() > 0 ? " - " + fixedFields[2] : "");
                        }
                    });

            functionMap.put(
                    "titlecase",
                    new Transform<>() {
                        @Override
                        public String transform(String source) {
                            return UCharacter.toTitleCase(source, null);
                        }
                    });
            functionMap.put(
                    "categoryFromScript",
                    new Transform<>() {
                        @Override
                        public String transform(String source) {
                            String script = hyphenSplitter.split(source);
                            Info info = ScriptMetadata.getInfo(script);
                            if (info == null) {
                                info = ScriptMetadata.getInfo("Zzzz");
                            }
                            order = 100 - info.idUsage.ordinal();
                            return info.idUsage.name;
                        }
                    });
            functionMap.put(
                    "categoryFromKey",
                    new Transform<>() {
                        final Map<String, String> fixNames =
                                Builder.with(new HashMap<String, String>())
                                        .put("cf", "Currency Format")
                                        .put("em", "Emoji Presentation")
                                        .put("fw", "First Day of Week")
                                        .put("lb", "Line Break")
                                        .put("hc", "Hour Cycle")
                                        .put("ms", "Measurement System")
                                        .put("ss", "Sentence Break Suppressions")
                                        .freeze();

                        @Override
                        public String transform(String source) {
                            String fixedName = fixNames.get(source);
                            return fixedName != null ? fixedName : source;
                        }
                    });
            functionMap.put(
                    "languageSection",
                    new Transform<>() {
                        final char[] languageRangeStartPoints = {'A', 'E', 'K', 'O', 'T'};
                        final char[] languageRangeEndPoints = {'D', 'J', 'N', 'S', 'Z'};

                        @Override
                        public String transform(String source0) {
                            char firstLetter = getEnglishFirstLetter(source0).charAt(0);
                            for (int i = 0; i < languageRangeStartPoints.length; i++) {
                                if (firstLetter >= languageRangeStartPoints[i]
                                        && firstLetter <= languageRangeEndPoints[i]) {
                                    return "Languages ("
                                            + Character.toUpperCase(languageRangeStartPoints[i])
                                            + "-"
                                            + Character.toUpperCase(languageRangeEndPoints[i])
                                            + ")";
                                }
                            }
                            return "Languages";
                        }
                    });
            functionMap.put(
                    "firstLetter",
                    new Transform<>() {
                        @Override
                        public String transform(String source0) {
                            return getEnglishFirstLetter(source0);
                        }
                    });
            functionMap.put(
                    "languageSort",
                    new Transform<>() {
                        @Override
                        public String transform(String source0) {
                            String languageOnlyPart;
                            int underscorePos = source0.indexOf("_");
                            if (underscorePos > 0) {
                                languageOnlyPart = source0.substring(0, underscorePos);
                            } else {
                                languageOnlyPart = source0;
                            }

                            return englishNameGetter.getNameFromTypeEnumCode(
                                            NameType.LANGUAGE, languageOnlyPart)
                                    + " \u25BA "
                                    + source0;
                        }
                    });
            functionMap.put(
                    "scriptFromLanguage",
                    new Transform<>() {
                        @Override
                        public String transform(String source0) {
                            String language = hyphenSplitter.split(source0);
                            String script = likelySubtags.getLikelyScript(language);
                            if (script == null) {
                                script = likelySubtags.getLikelyScript(language);
                            }
                            String scriptName =
                                    englishNameGetter.getNameFromTypeEnumCode(
                                            NameType.SCRIPT, script);
                            return "Languages in "
                                    + (script.equals("Hans") || script.equals("Hant")
                                            ? "Han Script"
                                            : scriptName.endsWith(" Script")
                                                    ? scriptName
                                                    : scriptName + " Script");
                        }
                    });
            functionMap.put(
                    "categoryFromTerritory",
                    catFromTerritory =
                            new Transform<>() {
                                @Override
                                public String transform(String source) {
                                    String territory = getSubdivisionsTerritory(source, null);
                                    String container = Containment.getContainer(territory);
                                    order = Containment.getOrder(territory);
                                    return englishNameGetter.getNameFromTypeEnumCode(
                                            NameType.TERRITORY, container);
                                }
                            });
            functionMap.put(
                    "territorySection",
                    new Transform<>() {
                        final Set<String> specialRegions =
                                new HashSet<>(Arrays.asList("EZ", "EU", "QO", "UN", "ZZ"));

                        @Override
                        public String transform(String source0) {
                            // support subdivisions
                            String theTerritory = getSubdivisionsTerritory(source0, null);
                            try {
                                if (specialRegions.contains(theTerritory)
                                        || theTerritory.charAt(0) < 'A'
                                                && Integer.parseInt(theTerritory) > 0) {
                                    return "Geographic Regions";
                                }
                            } catch (NumberFormatException ex) {
                            }
                            String theContinent = Containment.getContinent(theTerritory);
                            String theSubContinent;
                            switch (theContinent) { // was Integer.valueOf
                                case "019": // Americas - For the territorySection, we just group
                                    // North America & South America
                                    final String subcontinent =
                                            Containment.getSubcontinent(theTerritory);
                                    theSubContinent =
                                            subcontinent.equals("005")
                                                    ? "005"
                                                    : "003"; // was Integer.valueOf(subcontinent) ==
                                    // 5
                                    return "Territories ("
                                            + englishNameGetter.getNameFromTypeEnumCode(
                                                    NameType.TERRITORY, theSubContinent)
                                            + ")";
                                case "001":
                                case "ZZ":
                                    return "Geographic Regions"; // not in containment
                                default:
                                    return "Territories ("
                                            + englishNameGetter.getNameFromTypeEnumCode(
                                                    NameType.TERRITORY, theContinent)
                                            + ")";
                            }
                        }
                    });
            functionMap.put(
                    "categoryFromTimezone",
                    catFromTimezone =
                            new Transform<>() {
                                @Override
                                public String transform(String source0) {
                                    String territory = Containment.getRegionFromZone(source0);
                                    if (territory == null) {
                                        territory = "ZZ";
                                    }
                                    return catFromTerritory.transform(territory);
                                }
                            });
            functionMap.put(
                    "timeZonePage",
                    new Transform<>() {
                        Set<String> singlePageTerritories =
                                new HashSet<>(Arrays.asList("AQ", "RU", "ZZ"));

                        @Override
                        public String transform(String source0) {
                            String theTerritory = Containment.getRegionFromZone(source0);
                            if (theTerritory == null
                                    || "001".equals(theTerritory)
                                    || "ZZ".equals(theTerritory)) {
                                if ("Etc/Unknown".equals(source0)) {
                                    theTerritory = "ZZ";
                                    // TODO (ICU-23096): remove else-if branch below once ICU's
                                    // snapshot version is uploaded.
                                } else if ("America/Coyhaique".equals(source0)) {
                                    theTerritory = "CL";
                                } else {
                                    throw new IllegalArgumentException(
                                            "ICU needs zone update? Source: "
                                                    + source0
                                                    + "; Territory: "
                                                    + theTerritory);
                                }
                            }
                            if (singlePageTerritories.contains(theTerritory)) {
                                return englishNameGetter.getNameFromTypeEnumCode(
                                        NameType.TERRITORY, theTerritory);
                            }
                            String theContinent = Containment.getContinent(theTerritory);
                            final String subcontinent = Containment.getSubcontinent(theTerritory);
                            String theSubContinent;
                            switch (Integer.parseInt(theContinent)) {
                                case 9: // Oceania - For the timeZonePage, we group Australasia on
                                    // one page, and the rest of Oceania on the other.
                                    try {
                                        theSubContinent =
                                                subcontinent.equals("053") ? "053" : "009"; // was
                                        // Integer.valueOf(subcontinent) ==
                                        // 53
                                    } catch (NumberFormatException ex) {
                                        theSubContinent = "009";
                                    }
                                    return englishNameGetter.getNameFromTypeEnumCode(
                                            NameType.TERRITORY, theSubContinent);
                                case 19: // Americas - For the timeZonePage, we just group North
                                    // America & South America
                                    theSubContinent =
                                            Integer.parseInt(subcontinent) == 5 ? "005" : "003";
                                    return englishNameGetter.getNameFromTypeEnumCode(
                                            NameType.TERRITORY, theSubContinent);
                                case 142: // Asia
                                    return englishNameGetter.getNameFromTypeEnumCode(
                                            NameType.TERRITORY, subcontinent);
                                default:
                                    return englishNameGetter.getNameFromTypeEnumCode(
                                            NameType.TERRITORY, theContinent);
                            }
                        }
                    });

            functionMap.put(
                    "timezoneSorting",
                    new Transform<>() {
                        @Override
                        public String transform(String source) {
                            final List<String> codeValues =
                                    Arrays.asList(
                                            "generic-long",
                                            "generic-short",
                                            "standard-long",
                                            "standard-short",
                                            "daylight-long",
                                            "daylight-short");
                            if (codeValues.contains(source)) {
                                order = codeValues.indexOf(source);
                            } else {
                                order = codeValues.size();
                            }
                            return source;
                        }
                    });

            functionMap.put(
                    "tzdpField",
                    new Transform<>() {
                        @Override
                        public String transform(String source) {
                            Map<String, String> fieldNames =
                                    Builder.with(new HashMap<String, String>())
                                            .put("regionFormat", "Region Format - Generic")
                                            .put(
                                                    "regionFormat-standard",
                                                    "Region Format - Standard")
                                            .put(
                                                    "regionFormat-daylight",
                                                    "Region Format - Daylight")
                                            .put("gmtFormat", "GMT Format")
                                            .put("hourFormat", "GMT Hours/Minutes Format")
                                            .put("gmtZeroFormat", "GMT Zero Format")
                                            .put("gmtUnknownFormat", "GMT Unknown Format")
                                            .put("fallbackFormat", "Location Fallback Format")
                                            .freeze();
                            final List<String> fieldOrder =
                                    Arrays.asList(
                                            "regionFormat",
                                            "regionFormat-standard",
                                            "regionFormat-daylight",
                                            "gmtFormat",
                                            "hourFormat",
                                            "gmtZeroFormat",
                                            "gmtUnknownFormat",
                                            "fallbackFormat");

                            if (fieldOrder.contains(source)) {
                                order = fieldOrder.indexOf(source);
                            } else {
                                order = fieldOrder.size();
                            }

                            String result = fieldNames.get(source);
                            return result == null ? source : result;
                        }
                    });
            functionMap.put(
                    "unit",
                    new Transform<>() {
                        @Override
                        public String transform(String source) {
                            int m = unitOrder.indexOf(source);
                            order = m;
                            return source.substring(source.indexOf('-') + 1);
                        }
                    });

            functionMap.put(
                    "numericSort",
                    new Transform<>() {
                        // Probably only works well for small values, like -5 through +4.
                        @Override
                        public String transform(String source) {
                            Integer pos = Integer.parseInt(source) + 5;
                            suborder = new SubstringOrder(pos.toString());
                            return source;
                        }
                    });

            functionMap.put(
                    "metazone",
                    new Transform<>() {

                        @Override
                        public String transform(String source) {
                            if (PathHeader.UNIFORM_CONTINENTS) {
                                String container = getMetazonePageTerritory(source);
                                order = Containment.getOrder(container);
                                return englishNameGetter.getNameFromTypeEnumCode(
                                        NameType.TERRITORY, container);
                            } else {
                                String continent = metazoneToContinent.get(source);
                                if (continent == null) {
                                    continent = "UnknownT";
                                }
                                return continent;
                            }
                        }
                    });

            Object[][] ctto = {
                {"BUK", "MM"},
                {"CSD", "RS"},
                {"CSK", "CZ"},
                {"DDM", "DE"},
                {"EUR", "ZZ"},
                {"RHD", "ZW"},
                {"SUR", "RU"},
                {"TPE", "TL"},
                {"XAG", "ZZ"},
                {"XAU", "ZZ"},
                {"XBA", "ZZ"},
                {"XBB", "ZZ"},
                {"XBC", "ZZ"},
                {"XBD", "ZZ"},
                {"XDR", "ZZ"},
                {"XEU", "ZZ"},
                {"XFO", "ZZ"},
                {"XFU", "ZZ"},
                {"XPD", "ZZ"},
                {"XPT", "ZZ"},
                {"XRE", "ZZ"},
                {"XSU", "ZZ"},
                {"XTS", "ZZ"},
                {"XUA", "ZZ"},
                {"XXX", "ZZ"},
                {"YDD", "YE"},
                {"YUD", "RS"},
                {"YUM", "RS"},
                {"YUN", "RS"},
                {"YUR", "RS"},
                {"ZRN", "CD"},
                {"ZRZ", "CD"},
            };

            Object[][] sctc = {
                {"Northern America", "North America (C)"},
                {"Central America", "North America (C)"},
                {"Caribbean", "North America (C)"},
                {"South America", "South America (C)"},
                {"Northern Africa", "Northern Africa"},
                {"Western Africa", "Western Africa"},
                {"Middle Africa", "Middle Africa"},
                {"Eastern Africa", "Eastern Africa"},
                {"Southern Africa", "Southern Africa"},
                {"Europe", "Northern/Western Europe"},
                {"Northern Europe", "Northern/Western Europe"},
                {"Western Europe", "Northern/Western Europe"},
                {"Eastern Europe", "Southern/Eastern Europe"},
                {"Southern Europe", "Southern/Eastern Europe"},
                {"Western Asia", "Western Asia (C)"},
                {"Central Asia", "Central Asia (C)"},
                {"Eastern Asia", "Eastern Asia (C)"},
                {"Southern Asia", "Southern Asia (C)"},
                {"Southeast Asia", "Southeast Asia (C)"},
                {"Australasia", "Oceania (C)"},
                {"Melanesia", "Oceania (C)"},
                {"Micronesian Region", "Oceania (C)"}, // HACK
                {"Polynesia", "Oceania (C)"},
                {"Unknown Region", "Unknown Region (C)"},
            };

            final Map<String, String> currencyToTerritoryOverrides = CldrUtility.asMap(ctto);
            final Map<String, String> subContinentToContinent = CldrUtility.asMap(sctc);
            final Set<String> fundCurrencies =
                    new HashSet<>(
                            Arrays.asList(
                                    "CHE", "CHW", "CLF", "COU", "ECV", "MXV", "USN", "USS", "UYI",
                                    "XEU", "ZAL"));
            final Set<String> offshoreCurrencies = new HashSet<>(Arrays.asList("CNH"));
            // TODO: Put this into supplementalDataInfo ?

            functionMap.put(
                    "categoryFromCurrency",
                    new Transform<>() {
                        @Override
                        public String transform(String source0) {
                            String tenderOrNot = "";
                            String territory =
                                    likelySubtags.getLikelyTerritoryFromCurrency(source0);
                            if (territory == null) {
                                String tag;
                                if (fundCurrencies.contains(source0)) {
                                    tag = " (fund)";
                                } else if (offshoreCurrencies.contains(source0)) {
                                    tag = " (offshore)";
                                } else {
                                    tag = " (old)";
                                }
                                tenderOrNot = ": " + source0 + tag;
                            }
                            if (currencyToTerritoryOverrides.keySet().contains(source0)) {
                                territory = currencyToTerritoryOverrides.get(source0);
                            } else if (territory == null) {
                                territory = source0.substring(0, 2);
                            }

                            if (territory.equals("ZZ")) {
                                order = 999;
                                return englishNameGetter.getNameFromTypeEnumCode(
                                                NameType.TERRITORY, territory)
                                        + ": "
                                        + source0;
                            } else {
                                return catFromTerritory.transform(territory)
                                        + ": "
                                        + englishNameGetter.getNameFromTypeEnumCode(
                                                NameType.TERRITORY, territory)
                                        + tenderOrNot;
                            }
                        }
                    });
            functionMap.put(
                    "continentFromCurrency",
                    new Transform<>() {
                        @Override
                        public String transform(String source0) {
                            String subContinent;
                            String territory =
                                    likelySubtags.getLikelyTerritoryFromCurrency(source0);
                            if (currencyToTerritoryOverrides.keySet().contains(source0)) {
                                territory = currencyToTerritoryOverrides.get(source0);
                            } else if (territory == null) {
                                territory = source0.substring(0, 2);
                            }

                            if (territory.equals("ZZ")) {
                                order = 999;
                                subContinent =
                                        englishNameGetter.getNameFromTypeEnumCode(
                                                NameType.TERRITORY, territory);
                            } else {
                                subContinent = catFromTerritory.transform(territory);
                            }

                            String result =
                                    subContinentToContinent.get(
                                            subContinent); // the continent is the last word in the
                            // territory representation
                            return result;
                        }
                    });
            functionMap.put(
                    "numberingSystem",
                    new Transform<>() {
                        @Override
                        public String transform(String source0) {
                            if ("latn".equals(source0)) {
                                return "";
                            }
                            String displayName =
                                    englishFile.getStringValue(
                                            "//ldml/localeDisplayNames/types/type[@key=\"numbers\"][@type=\""
                                                    + source0
                                                    + "\"]");
                            return "using "
                                    + (displayName == null
                                            ? source0
                                            : displayName + " (" + source0 + ")");
                        }
                    });

            functionMap.put(
                    "datefield",
                    new Transform<>() {
                        private final String[] datefield = {
                            "era", "era-short", "era-narrow",
                            "century", "century-short", "century-narrow",
                            "year", "year-short", "year-narrow",
                            "quarter", "quarter-short", "quarter-narrow",
                            "month", "month-short", "month-narrow",
                            "week", "week-short", "week-narrow",
                            "weekOfMonth", "weekOfMonth-short", "weekOfMonth-narrow",
                            "day", "day-short", "day-narrow",
                            "dayOfYear", "dayOfYear-short", "dayOfYear-narrow",
                            "weekday", "weekday-short", "weekday-narrow",
                            "weekdayOfMonth", "weekdayOfMonth-short", "weekdayOfMonth-narrow",
                            "dayperiod", "dayperiod-short", "dayperiod-narrow",
                            "zone", "zone-short", "zone-narrow",
                            "hour", "hour-short", "hour-narrow",
                            "minute", "minute-short", "minute-narrow",
                            "second", "second-short", "second-narrow",
                            "millisecond", "millisecond-short", "millisecond-narrow",
                            "microsecond", "microsecond-short", "microsecond-narrow",
                            "nanosecond", "nanosecond-short", "nanosecond-narrow",
                        };

                        @Override
                        public String transform(String source) {
                            order = getIndex(source, datefield);
                            return source;
                        }
                    });
            // //ldml/dates/fields/field[@type="%A"]/relative[@type="%A"]
            functionMap.put(
                    "relativeDate",
                    new Transform<>() {
                        private final String[] relativeDateField = {
                            "year", "year-short", "year-narrow",
                            "quarter", "quarter-short", "quarter-narrow",
                            "month", "month-short", "month-narrow",
                            "week", "week-short", "week-narrow",
                            "day", "day-short", "day-narrow",
                            "hour", "hour-short", "hour-narrow",
                            "minute", "minute-short", "minute-narrow",
                            "second", "second-short", "second-narrow",
                            "sun", "sun-short", "sun-narrow",
                            "mon", "mon-short", "mon-narrow",
                            "tue", "tue-short", "tue-narrow",
                            "wed", "wed-short", "wed-narrow",
                            "thu", "thu-short", "thu-narrow",
                            "fri", "fri-short", "fri-narrow",
                            "sat", "sat-short", "sat-narrow",
                        };
                        private final String[] longNames = {
                            "Year", "Year Short", "Year Narrow",
                            "Quarter", "Quarter Short", "Quarter Narrow",
                            "Month", "Month Short", "Month Narrow",
                            "Week", "Week Short", "Week Narrow",
                            "Day", "Day Short", "Day Narrow",
                            "Hour", "Hour Short", "Hour Narrow",
                            "Minute", "Minute Short", "Minute Narrow",
                            "Second", "Second Short", "Second Narrow",
                            "Sunday", "Sunday Short", "Sunday Narrow",
                            "Monday", "Monday Short", "Monday Narrow",
                            "Tuesday", "Tuesday Short", "Tuesday Narrow",
                            "Wednesday", "Wednesday Short", "Wednesday Narrow",
                            "Thursday", "Thursday Short", "Thursday Narrow",
                            "Friday", "Friday Short", "Friday Narrow",
                            "Saturday", "Saturday Short", "Saturday Narrow",
                        };

                        @Override
                        public String transform(String source) {
                            order = getIndex(source, relativeDateField) + 100;
                            return "Relative " + longNames[getIndex(source, relativeDateField)];
                        }
                    });
            // Sorts numberSystem items (except for decimal formats).
            functionMap.put(
                    "number",
                    new Transform<>() {
                        private final String[] symbols = {
                            "decimal",
                            "group",
                            "plusSign",
                            "minusSign",
                            "approximatelySign",
                            "percentSign",
                            "perMille",
                            "exponential",
                            "superscriptingExponent",
                            "infinity",
                            "nan",
                            "list",
                            "currencies"
                        };

                        @Override
                        public String transform(String source) {
                            String[] parts = source.split("-");
                            order = getIndex(parts[0], symbols);
                            // e.g. "currencies-one"
                            if (parts.length > 1) {
                                suborder = new SubstringOrder(parts[1]);
                            }
                            return source;
                        }
                    });
            functionMap.put(
                    "numberFormat",
                    new Transform<>() {
                        @Override
                        public String transform(String source) {
                            final List<String> fieldOrder =
                                    Arrays.asList(
                                            "standard-decimal",
                                            "standard-currency",
                                            "standard-currency-accounting",
                                            "standard-percent",
                                            "standard-scientific");

                            if (fieldOrder.contains(source)) {
                                order = fieldOrder.indexOf(source);
                            } else {
                                order = fieldOrder.size();
                            }

                            return source;
                        }
                    });

            functionMap.put(
                    "localePattern",
                    new Transform<>() {
                        @Override
                        public String transform(String source) {
                            // Put localeKeyTypePattern behind localePattern and
                            // localeSeparator.
                            if (source.equals("localeKeyTypePattern")) {
                                order = 10;
                            }
                            return source;
                        }
                    });
            functionMap.put(
                    "listOrder",
                    new Transform<>() {
                        private String[] listParts = {"2", "start", "middle", "end"};

                        @Override
                        public String transform(String source) {
                            order = getIndex(source, listParts);
                            return source;
                        }
                    });

            functionMap.put(
                    "personNameSection",
                    new Transform<>() {
                        @Override
                        public String transform(String source) {
                            // sampleName item values in desired sort order
                            final List<String> itemValues =
                                    PersonNameFormatter.SampleType.ALL_STRINGS;
                            if (source.equals("NameOrder")) {
                                order = 0;
                                return "NameOrder for Locales";
                            }
                            if (source.equals("Parameters")) {
                                order = 4;
                                return "Default Parameters";
                            }

                            if (source.equals("AuxiliaryItems")) {
                                order = 10;
                                return source;
                            }
                            String itemPrefix = "SampleName:";
                            if (source.startsWith(itemPrefix)) {
                                String itemValue = source.substring(itemPrefix.length());
                                order = 20 + itemValues.indexOf(itemValue);
                                return "SampleName Fields for Item: " + itemValue;
                            }
                            String pnPrefix = "PersonName:";
                            if (source.startsWith(pnPrefix)) {
                                String attrValues = source.substring(pnPrefix.length());
                                List<String> parts = HYPHEN_SPLITTER.splitToList(attrValues);

                                String nameOrder = parts.get(0);
                                if (nameOrder.contentEquals("sorting")) {
                                    order = 40;
                                    return "PersonName Sorting Patterns (Usage: referring)";
                                }
                                order = 30;
                                if (nameOrder.contentEquals("surnameFirst")) {
                                    order += 1;
                                }
                                String nameUsage = parts.get(1);
                                if (nameUsage.contentEquals("monogram")) {
                                    order += 20;
                                    return "PersonName Monogram Patterns for Order: " + nameOrder;
                                }
                                return "PersonName Main Patterns for Order: " + nameOrder;
                            }
                            order = 60;
                            return source;
                        }
                    });

            functionMap.put(
                    "personNameOrder",
                    new Transform<>() {
                        @Override
                        public String transform(String source) {
                            // personName attribute values: each group in desired
                            // sort order, but groups from least important to most
                            final List<String> attrValues =
                                    Arrays.asList(
                                            "referring",
                                            "addressing", // usage values to include
                                            "formal",
                                            "informal", // formality values
                                            "long",
                                            "medium",
                                            "short"); // length values
                            // order & length values handled in &personNameSection

                            List<String> parts = HYPHEN_SPLITTER.splitToList(source);
                            order = 0;
                            String attributes = "";
                            boolean skipReferring = false;
                            for (String part : parts) {
                                if (attrValues.contains(part)) {
                                    order += (1 << attrValues.indexOf(part));
                                    // anything else like alt="variant" is at order 0
                                    if (!skipReferring || !part.contentEquals("referring")) {
                                        // Add this part to display attribute string
                                        if (attributes.length() == 0) {
                                            attributes = part;
                                        } else {
                                            attributes = attributes + "-" + part;
                                        }
                                    }
                                } else if (part.contentEquals("sorting")) {
                                    skipReferring = true; // For order=sorting, don't display
                                    // usage=referring
                                }
                            }
                            return attributes;
                        }
                    });

            functionMap.put(
                    "sampleNameOrder",
                    new Transform<>() {
                        @Override
                        public String transform(String source) {
                            // The various nameField attribute values: each group in desired
                            // sort order, but groups from least important to most
                            final List<String> attrValues =
                                    Arrays.asList(
                                            "informal",
                                            "prefix",
                                            "core", // modifiers for nameField type
                                            "prefix",
                                            "given",
                                            "given2",
                                            "surname",
                                            "surname2",
                                            "suffix"); // values for nameField type

                            List<String> parts = HYPHEN_SPLITTER.splitToList(source);
                            order = 0;
                            for (String part : parts) {
                                if (attrValues.contains(part)) {
                                    order += (1 << attrValues.indexOf(part));
                                } // anything else like alt="variant" is at order 0
                            }
                            return source;
                        }
                    });

            functionMap.put(
                    "alphaOrder",
                    new Transform<>() {
                        @Override
                        public String transform(String source) {
                            order = 0;
                            return source;
                        }
                    });
            functionMap.put(
                    "transform",
                    new Transform<>() {
                        Splitter commas = Splitter.on(',').trimResults();

                        @Override
                        public String transform(String source) {
                            List<String> parts = commas.splitToList(source);
                            return parts.get(1)
                                    + (parts.get(0).equals("both") ? "↔︎" : "→")
                                    + parts.get(2)
                                    + (parts.size() > 3 ? "/" + parts.get(3) : "");
                        }
                    });
            functionMap.put(
                    "major",
                    new Transform<>() {
                        @Override
                        public String transform(String source) {
                            return getCharacterPageId(source).toString();
                        }
                    });
            functionMap.put(
                    "minor",
                    new Transform<>() {
                        @Override
                        public String transform(String source) {
                            String minorCat = Emoji.getMinorCategory(source);
                            order = Emoji.getEmojiMinorOrder(minorCat);
                            return minorCat;
                        }
                    });
            /**
             * Use the ordering of the emoji in getEmojiToOrder rather than alphabetic, since the
             * collator data won't be ready until the candidates are final.
             */
            functionMap.put(
                    "emoji",
                    new Transform<>() {
                        @Override
                        public String transform(String source) {
                            int dashPos = source.indexOf(' ');
                            String emoji = source.substring(0, dashPos);
                            order =
                                    (Emoji.getEmojiToOrder(emoji) << 1)
                                            + (source.endsWith("name") ? 0 : 1);
                            return source;
                        }
                    });
        }

        private static int getIndex(String item, String[] array) {
            for (int i = 0; i < array.length; i++) {
                if (item.equals(array[i])) {
                    return i;
                }
            }
            return -1;
        }

        private static String getEnglishFirstLetter(String s) {
            String languageOnlyPart;
            int underscorePos = s.indexOf("_");
            if (underscorePos > 0) {
                languageOnlyPart = s.substring(0, underscorePos);
            } else {
                languageOnlyPart = s;
            }
            final String name =
                    englishNameGetter.getNameFromTypeEnumCode(NameType.LANGUAGE, languageOnlyPart);
            return name == null ? "?" : name.substring(0, 1).toUpperCase();
        }

        static class HyphenSplitter {
            String main;
            String extras;

            String split(String source) {
                int hyphenPos = source.indexOf('-');
                if (hyphenPos < 0) {
                    main = source;
                    extras = "";
                } else {
                    main = source.substring(0, hyphenPos);
                    extras = source.substring(hyphenPos);
                }
                return main;
            }
        }

        /**
         * This converts "functions", like &month, and sets the order.
         *
         * @param input
         * @param orderIn
         * @return
         */
        private static String fix(String input, int orderIn) {
            input = RegexLookup.replace(input, args.value);
            order = orderIn;
            suborder = null;
            int pos = 0;
            while (true) {
                int functionStart = input.indexOf('&', pos);
                if (functionStart < 0) {
                    return adjustPageForPath(input, args.value[0] /* path */).toString();
                }
                int functionEnd = input.indexOf('(', functionStart);
                int argEnd =
                        input.indexOf(
                                ')', functionEnd + 2); // we must insert at least one character
                Transform<String, String> func =
                        functionMap.get(input.substring(functionStart + 1, functionEnd));
                final String arg = input.substring(functionEnd + 1, argEnd);
                String temp = func.transform(arg);
                if (temp == null) {
                    func.transform(arg);
                    throw new IllegalArgumentException(
                            "Function returns invalid results for «" + arg + "».");
                }
                input = input.substring(0, functionStart) + temp + input.substring(argEnd + 1);
                pos = functionStart + temp.length();
            }
        }

        private static String adjustPageForPath(String input, String path) {
            if ("Fields".equals(input)) {
                return getFieldsPageId(path).toString();
            }
            if ("Length".equals(input)) {
                return getLengthPageId(path).toString();
            }
            if ("Other Units".equals(input)) {
                return getOtherUnitsPageId(path).toString();
            }
            if ("Volume".equals(input)) {
                return getVolumePageId(path).toString();
            }
            return input;
        }

        private static PageId getFieldsPageId(String path) {
            XPathParts parts = XPathParts.getFrozenInstance(path);
            return (parts.containsElement("relative")
                            || parts.containsElement("relativeTime")
                            || parts.containsElement("relativePeriod"))
                    ? PageId.Relative
                    : PageId.Fields;
        }

        private static Set<UnitConverter.UnitSystem> METRIC_UNITS =
                Set.of(UnitConverter.UnitSystem.metric, UnitConverter.UnitSystem.metric_adjacent);

        private static Set<UnitConverter.UnitSystem> US_UNITS =
                Set.of(UnitConverter.UnitSystem.ussystem);

        private static PageId getLengthPageId(String path) {
            final String shortUnitId = getShortUnitId(path);
            if (isSystemUnit(shortUnitId, METRIC_UNITS)) {
                return PageId.Length_Metric;
            } else {
                // Could further subdivide into US/Other with isSystemUnit(shortUnitId, US_UNITS)
                return PageId.Length_Other;
            }
        }

        private static PageId getVolumePageId(String path) {
            final String shortUnitId = getShortUnitId(path);
            if (isSystemUnit(shortUnitId, METRIC_UNITS)) {
                return PageId.Volume_Metric;
            } else {
                return isSystemUnit(shortUnitId, US_UNITS) ? PageId.Volume_US : PageId.Volume_Other;
            }
        }

        private static PageId getOtherUnitsPageId(String path) {
            String shortUnitId = getShortUnitId(path);
            if (isSystemUnit(shortUnitId, METRIC_UNITS)) {
                return shortUnitId.contains("per")
                        ? PageId.OtherUnitsMetricPer
                        : PageId.OtherUnitsMetric;
            } else {
                return isSystemUnit(shortUnitId, US_UNITS)
                        ? PageId.OtherUnitsUS
                        : PageId.OtherUnits;
            }
        }

        private static boolean isSystemUnit(
                String shortUnitId, Set<UnitConverter.UnitSystem> system) {
            final UnitConverter uc = supplementalDataInfo.getUnitConverter();
            final Set<UnitConverter.UnitSystem> systems = uc.getSystemsEnum(shortUnitId);
            return !Collections.disjoint(system, systems);
        }

        private static String getShortUnitId(String path) {
            // Extract the unit from the path. For example, if path is
            // //ldml/units/unitLength[@type="narrow"]/unit[@type="volume-cubic-kilometer"]/displayName
            // then extract "volume-cubic-kilometer" which is the long unit id
            final String longUnitId =
                    XPathParts.getFrozenInstance(path).findAttributeValue("unit", "type");
            if (longUnitId == null) {
                throw new InternalCldrException("Missing unit in path " + path);
            }
            final UnitConverter uc = supplementalDataInfo.getUnitConverter();
            // Convert, for example, "volume-cubic-kilometer" to "cubic-kilometer"
            return uc.getShortId(longUnitId);
        }

        /**
         * Collect all the paths for a CLDRFile, and make sure that they have cached PathHeaders
         *
         * @param file
         * @return immutable set of paths in the file
         */
        public Set<String> pathsForFile(CLDRFile file) {
            // make sure we cache all the path headers
            HashSet<String> filePaths = new HashSet<>();
            file.fullIterable().forEach(filePaths::add);
            for (String path : filePaths) {
                try {
                    fromPath(path); // call to make sure cached
                } catch (Throwable t) {
                    // ... some other exception
                }
            }
            return Collections.unmodifiableSet(filePaths);
        }

        /**
         * Returns those regexes that were never matched.
         *
         * @return
         */
        public Set<String> getUnmatchedRegexes() {
            Map<String, RawData> outputUnmatched = new LinkedHashMap<>();
            lookup.getUnmatchedPatterns(matchersFound, outputUnmatched);
            return outputUnmatched.keySet();
        }
    }

    /**
     * Return the territory used for the title of the Metazone page in the Survey Tool.
     *
     * @param source
     * @return
     */
    public static String getMetazonePageTerritory(String source) {
        String result = metazoneToPageTerritory.get(source);
        return result == null ? "ZZ" : result;
    }

    private static final List<String> COUNTS =
            Arrays.asList("displayName", "zero", "one", "two", "few", "many", "other", "per");

    private static Collator alphabetic;

    private static int alphabeticCompare(String aa, String bb) {
        if (alphabetic == null) {
            initializeAlphabetic();
        }
        return alphabetic.compare(aa, bb);
    }

    private static synchronized void initializeAlphabetic() {
        // Lazy initialization: don't call CLDRConfig.getInstance() too early or we'll get
        // "CLDRConfig.getInstance() was called prior to SurveyTool setup" when called from
        // com.ibm.ws.microprofile.openapi.impl.core.jackson.ModelResolver._addEnumProps
        if (alphabetic == null) {
            alphabetic = CLDRConfig.getInstance().getCollatorRoot();
        }
    }

    /**
     * @deprecated use CLDRConfig.getInstance().urls() instead
     */
    @Deprecated
    public enum BaseUrl {
        // http://st.unicode.org/smoketest/survey?_=af&strid=55053dffac611328
        // http://st.unicode.org/cldr-apps/survey?_=en&strid=3cd31261bf6738e1
        SMOKE("https://st.unicode.org/smoketest/survey"),
        PRODUCTION("https://st.unicode.org/cldr-apps/survey");
        final String base;

        private BaseUrl(String url) {
            base = url;
        }
    }

    /**
     * @deprecated, use CLDRConfig.urls().forPathHeader() instead.
     * @param baseUrl
     * @param locale
     * @return
     */
    public String getUrl(BaseUrl baseUrl, String locale) {
        return getUrl(baseUrl.base, locale);
    }

    /**
     * @deprecated, use CLDRConfig.urls().forPathHeader() instead.
     * @param baseUrl
     * @param locale
     * @return
     */
    public String getUrl(String baseUrl, String locale) {
        return getUrl(baseUrl, locale, getOriginalPath());
    }

    /**
     * Map http://st.unicode.org/smoketest/survey to http://st.unicode.org/smoketest etc
     *
     * @param str
     * @return
     */
    public static String trimLast(String str) {
        int n = str.lastIndexOf('/');
        if (n == -1) return "";
        return str.substring(0, n + 1);
    }

    public static String getUrlForLocalePath(String locale, String path) {
        return getUrl(SURVEY_URL, locale, path);
    }

    public static String getUrl(String baseUrl, String locale, String path) {
        return trimLast(baseUrl) + "v#/" + locale + "//" + StringId.getHexId(path);
    }

    /**
     * @deprecated use the version with CLDRURLS instead
     * @param baseUrl
     * @param file
     * @param path
     * @return
     */
    @Deprecated
    public static String getLinkedView(String baseUrl, CLDRFile file, String path) {
        return SECTION_LINK
                + PathHeader.getUrl(baseUrl, file.getLocaleID(), path)
                + "'><em>view</em></a>";
    }

    public static String getLinkedView(CLDRURLS urls, CLDRFile file, String path) {
        return SECTION_LINK + urls.forXpath(file.getLocaleID(), path) + "'><em>view</em></a>";
    }

    private static final String SURVEY_URL = CLDRConfig.getInstance().urls().base();

    /**
     * If a subdivision, return the (uppercased) territory and if suffix != null, the suffix.
     * Otherwise return the input as is.
     *
     * @param input
     * @param suffix
     * @return
     */
    private static String getSubdivisionsTerritory(String input, Output<String> suffix) {
        String theTerritory;
        if (StandardCodes.LstrType.subdivision.isWellFormed(input)) {
            int territoryEnd = input.charAt(0) < 'A' ? 3 : 2;
            theTerritory = input.substring(0, territoryEnd).toUpperCase(Locale.ROOT);
            if (suffix != null) {
                suffix.value = input.substring(territoryEnd);
            }
        } else {
            theTerritory = input;
            if (suffix != null) {
                suffix.value = "";
            }
        }
        return theTerritory;
    }

    /**
     * Should this path header be hidden?
     *
     * @return true to hide, else false
     */
    public boolean shouldHide() {
        switch (status) {
            case HIDE:
            case DEPRECATED:
                return true;
            case READ_ONLY:
            case READ_WRITE:
            case LTR_ALWAYS:
                return false;
            default:
                logger.log(java.util.logging.Level.SEVERE, "Missing case for " + status);
                return false;
        }
    }

    /**
     * Are reading and writing allowed for this path header?
     *
     * @return true if reading and writing are allowed, else false
     */
    public boolean canReadAndWrite() {
        switch (status) {
            case READ_WRITE:
            case LTR_ALWAYS:
                return true;
            case HIDE:
            case DEPRECATED:
            case READ_ONLY:
                return false;
            default:
                logger.log(java.util.logging.Level.SEVERE, "Missing case for " + status);
                return false;
        }
    }

    private static UnicodeMap<PageId> nonEmojiMap = null;

    /**
     * Return the PageId for the given character
     *
     * @param cp the character as a string
     * @return the PageId
     */
    private static PageId getCharacterPageId(String cp) {
        if (Emoji.getAllRgiNoES().contains(cp)) {
            return Emoji.getPageId(cp);
        }
        if (nonEmojiMap == null) {
            nonEmojiMap = createNonEmojiMap();
        }
        PageId pageId = nonEmojiMap.get(cp);
        if (pageId == null) {
            throw new InternalCldrException("Failure getting character page id");
        }
        return pageId;
    }

    /**
     * Create the map from non-emoji characters to pages. Call with lazy initialization to avoid
     * static initialization bugs, otherwise PageId.OtherSymbols could still be null.
     *
     * @return the map from character to PageId
     */
    private static UnicodeMap<PageId> createNonEmojiMap() {
        return new UnicodeMap<PageId>()
                .putAll(new UnicodeSet("[:P:]"), PageId.Punctuation)
                .putAll(new UnicodeSet("[:Sm:]"), PageId.MathSymbols)
                .putAll(new UnicodeSet("[^[:Sm:][:P:]]"), PageId.OtherSymbols)
                .freeze();
    }
}
