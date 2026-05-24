package com.bot.service;

import com.bot.client.PythonMLClient;
import com.bot.model.NewsItem;
import com.bot.util.NewsDisplayText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.bot.util.TextUtils.defaultText;
import static com.bot.util.TextUtils.hasText;

class DetailSelector {

    private static final Pattern DETAIL_INDEX_PATTERN = Pattern.compile("^(?:第)?(\\d{1,2})(?:条|个|则|项)?$");
    private static final Pattern NON_QUERY_TEXT_PATTERN = Pattern.compile("[^\\p{IsHan}a-zA-Z0-9]+");
    private static final Pattern DETAIL_QUERY_TOKEN_PATTERN = Pattern.compile("[a-z0-9]+|[\\p{IsHan}]+");
    private static final double SEMANTIC_AUTO_SELECT_SCORE = 0.72d;
    private static final double SEMANTIC_AUTO_SELECT_GAP = 0.08d;
    private static final double SEMANTIC_CLARIFY_SCORE = 0.56d;
    private static final int SEMANTIC_TOP_K = 5;
    private static final int CLARIFY_CANDIDATE_LIMIT = 3;

    private final PythonMLClient mlClient;
    private final int snapshotLimit;
    private final Function<NewsItem, String> detailExcerptResolver;

    DetailSelector(PythonMLClient mlClient,
                   int snapshotLimit,
                   Function<NewsItem, String> detailExcerptResolver) {
        this.mlClient = mlClient;
        this.snapshotLimit = snapshotLimit;
        this.detailExcerptResolver = detailExcerptResolver;
    }

    DetailSelection select(String content, List<NewsItem> items, Map<String, String> translatedTitles) {
        if (items == null || items.isEmpty()) {
            return DetailSelection.none();
        }

        Integer selectedIndex = parseDetailIndexSelection(content, items.size());
        if (selectedIndex != null) {
            return DetailSelection.match(items.get(selectedIndex - 1));
        }

        String query = simplifyDetailSelectionText(content);
        if (!hasText(query)) {
            return DetailSelection.none();
        }

        List<NewsItem> matchedItems = items.stream()
                .filter(item -> matchesDetailQuery(item, query, translatedTitles))
                .toList();
        if (matchedItems.size() == 1) {
            return DetailSelection.match(matchedItems.get(0));
        }

        DetailSelection semanticSelection = matchedItems.isEmpty()
                ? resolveSemanticDetailSelection(query, items, items, translatedTitles)
                : resolveSemanticDetailSelection(query, items, matchedItems, translatedTitles);
        if (semanticSelection.matched() || semanticSelection.clarificationReply() != null) {
            return semanticSelection;
        }

        if (matchedItems.size() > 1) {
            return DetailSelection.clarify("匹配到多条内容，请直接回复 1-" + Math.min(items.size(), snapshotLimit) + " 或更具体一点的标题。");
        }
        return DetailSelection.none();
    }

    private DetailSelection resolveSemanticDetailSelection(String query,
                                                           List<NewsItem> snapshotItems,
                                                           List<NewsItem> candidateItems,
                                                           Map<String, String> translatedTitles) {
        if (candidateItems == null || candidateItems.isEmpty() || !hasText(query) || mlClient == null) {
            return DetailSelection.none();
        }

        List<String> candidateTexts = candidateItems.stream()
                .map(item -> buildSemanticCandidateText(item, translatedTitles))
                .toList();
        List<PythonMLClient.SemanticRankMatch> rankedMatches = mlClient.rankCandidates(
                query,
                candidateTexts,
                Math.min(SEMANTIC_TOP_K, candidateTexts.size()));
        if (rankedMatches == null || rankedMatches.isEmpty()) {
            return DetailSelection.none();
        }

        List<RankedNewsCandidate> rankedCandidates = rankedMatches.stream()
                .filter(match -> match.index() >= 0 && match.index() < candidateItems.size())
                .map(match -> new RankedNewsCandidate(candidateItems.get(match.index()), match))
                .sorted(Comparator.comparingDouble((RankedNewsCandidate candidate) -> candidate.match().score()).reversed())
                .toList();
        if (rankedCandidates.isEmpty()) {
            return DetailSelection.none();
        }

        RankedNewsCandidate best = rankedCandidates.get(0);
        double secondScore = rankedCandidates.size() > 1 ? rankedCandidates.get(1).match().score() : 0d;
        double gap = best.match().score() - secondScore;
        if (best.match().score() >= SEMANTIC_AUTO_SELECT_SCORE && gap >= SEMANTIC_AUTO_SELECT_GAP) {
            return DetailSelection.match(best.item());
        }
        if (rankedCandidates.size() > 1 && best.match().score() >= SEMANTIC_CLARIFY_SCORE) {
            return DetailSelection.clarify(buildSemanticClarificationReply(snapshotItems, rankedCandidates));
        }
        return DetailSelection.none();
    }

    private Integer parseDetailIndexSelection(String content, int maxIndex) {
        String normalized = normalizeText(content);
        var matcher = DETAIL_INDEX_PATTERN.matcher(normalized);
        if (matcher.matches()) {
            int selected = Integer.parseInt(matcher.group(1));
            return selected >= 1 && selected <= maxIndex ? selected : null;
        }

        String simplified = simplifyDetailSelectionText(content);
        return switch (simplified) {
            case "一", "第一", "第一条", "第一则", "第一项" -> maxIndex >= 1 ? 1 : null;
            case "二", "第二", "第二条", "第二则", "第二项" -> maxIndex >= 2 ? 2 : null;
            case "三", "第三", "第三条", "第三则", "第三项" -> maxIndex >= 3 ? 3 : null;
            case "四", "第四", "第四条", "第四则", "第四项" -> maxIndex >= 4 ? 4 : null;
            case "五", "第五", "第五条", "第五则", "第五项" -> maxIndex >= 5 ? 5 : null;
            case "六", "第六", "第六条", "第六则", "第六项" -> maxIndex >= 6 ? 6 : null;
            case "七", "第七", "第七条", "第七则", "第七项" -> maxIndex >= 7 ? 7 : null;
            case "八", "第八", "第八条", "第八则", "第八项" -> maxIndex >= 8 ? 8 : null;
            case "九", "第九", "第九条", "第九则", "第九项" -> maxIndex >= 9 ? 9 : null;
            case "十", "第十", "第十条", "第十则", "第十项" -> maxIndex >= 10 ? 10 : null;
            case "十一", "第十一", "第十一条", "第十一则", "第十一项" -> maxIndex >= 11 ? 11 : null;
            case "十二", "第十二", "第十二条", "第十二则", "第十二项" -> maxIndex >= 12 ? 12 : null;
            default -> null;
        };
    }

    private String simplifyDetailSelectionText(String content) {
        return IntentKeywords.simplifyDetailSelectionText(content);
    }

    private boolean matchesDetailQuery(NewsItem item, String query, Map<String, String> translatedTitles) {
        String normalizedQuery = normalizeQueryText(query);
        if (!hasText(normalizedQuery)) {
            return false;
        }
        String title = normalizeQueryText(item == null ? null : item.getTitle());
        String displayTitle = normalizeQueryText(resolveDisplayTitle(item, translatedTitles));
        List<String> tokens = extractDetailQueryTokens(query);
        return title.contains(normalizedQuery)
                || displayTitle.contains(normalizedQuery)
                || normalizedQuery.contains(title)
                || normalizedQuery.contains(displayTitle)
                || tokens.stream().anyMatch(token -> title.contains(token) || displayTitle.contains(token))
                || fuzzyDetailMatch(normalizedQuery, title)
                || fuzzyDetailMatch(normalizedQuery, displayTitle);
    }

    private String normalizeQueryText(String text) {
        return NON_QUERY_TEXT_PATTERN.matcher(defaultText(text, "").toLowerCase()).replaceAll("");
    }

    private List<String> extractDetailQueryTokens(String query) {
        var matcher = DETAIL_QUERY_TOKEN_PATTERN.matcher(defaultText(query, "").toLowerCase());
        List<String> tokens = new ArrayList<>();
        while (matcher.find()) {
            String token = matcher.group();
            if (!isAsciiAlphaNumeric(token)) {
                token = IntentKeywords.stripDetailSelectionTokenNoise(token);
            }
            if (!hasText(token)) {
                continue;
            }
            if (isAsciiAlphaNumeric(token) && token.length() < 3) {
                continue;
            }
            if (!isAsciiAlphaNumeric(token) && token.length() < 2) {
                continue;
            }
            if (!tokens.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private boolean isAsciiAlphaNumeric(String token) {
        return token.chars().allMatch(ch -> (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9'));
    }

    private boolean fuzzyDetailMatch(String query, String candidate) {
        if (!hasText(query) || !hasText(candidate)) {
            return false;
        }
        if (isMostlyChinese(query) && isMostlyChinese(candidate)) {
            return bigramOverlapScore(query, candidate) >= 0.55d;
        }
        return lcsScore(query, candidate) >= 0.7d;
    }

    private boolean isMostlyChinese(String text) {
        long hanCount = defaultText(text, "").codePoints()
                .filter(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN)
                .count();
        return hanCount >= Math.max(2, text.length() / 2);
    }

    private double bigramOverlapScore(String query, String candidate) {
        if (query.length() < 2 || candidate.length() < 2) {
            return 0d;
        }
        List<String> grams = new ArrayList<>();
        for (int index = 0; index < query.length() - 1; index++) {
            String gram = query.substring(index, index + 2);
            if (!grams.contains(gram)) {
                grams.add(gram);
            }
        }
        long hits = grams.stream().filter(candidate::contains).count();
        return grams.isEmpty() ? 0d : hits / (double) grams.size();
    }

    private double lcsScore(String query, String candidate) {
        int queryLength = query.length();
        int candidateLength = candidate.length();
        int[][] dp = new int[queryLength + 1][candidateLength + 1];
        for (int i = 1; i <= queryLength; i++) {
            for (int j = 1; j <= candidateLength; j++) {
                if (query.charAt(i - 1) == candidate.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }
        return queryLength == 0 ? 0d : dp[queryLength][candidateLength] / (double) queryLength;
    }

    private String buildSemanticCandidateText(NewsItem item, Map<String, String> translatedTitles) {
        List<String> parts = new ArrayList<>();
        parts.add(defaultText(item == null ? null : item.getTitle(), ""));
        String displayTitle = resolveDisplayTitle(item, translatedTitles);
        if (hasText(displayTitle) && !displayTitle.equals(item == null ? null : item.getTitle())) {
            parts.add(displayTitle);
        }
        parts.add(defaultText(item == null ? null : item.getSource(), ""));
        parts.add(NewsDisplayText.displayCategory(item == null ? null : item.getCategory()));
        parts.add(defaultText(item == null ? null : item.summaryPreviewText(), ""));
        parts.add(limit(resolveDetailExcerpt(item), 220));
        return parts.stream()
            .filter(com.bot.util.TextUtils::hasText)
                .collect(Collectors.joining("\n"));
    }

    private String buildSemanticClarificationReply(List<NewsItem> snapshotItems, List<RankedNewsCandidate> rankedCandidates) {
        List<String> lines = new ArrayList<>();
        lines.add("我更可能指这几条，请直接回复序号：");
        rankedCandidates.stream()
                .limit(CLARIFY_CANDIDATE_LIMIT)
                .forEach(candidate -> lines.add(resolveSnapshotIndex(snapshotItems, candidate.item()) + ". " + defaultText(candidate.item().getTitle(), "无标题")));
        return String.join("\n", lines);
    }

    private int resolveSnapshotIndex(List<NewsItem> snapshotItems, NewsItem item) {
        for (int index = 0; index < snapshotItems.size(); index++) {
            NewsItem candidate = snapshotItems.get(index);
            if (Objects.equals(candidate.getId(), item.getId())) {
                return index + 1;
            }
        }
        return 1;
    }

    private String resolveDisplayTitle(NewsItem item, Map<String, String> translatedTitles) {
        if (item == null) {
            return "无标题";
        }
        String originalTitle = item.getTitle();
        String translatedTitle = translatedTitles == null ? null : translatedTitles.get(originalTitle);
        return hasText(translatedTitle) ? translatedTitle : defaultText(originalTitle, "无标题");
    }

    private String resolveDetailExcerpt(NewsItem item) {
        if (item == null) {
            return "";
        }
        if (detailExcerptResolver == null) {
            return defaultText(item.resolvedDetailExcerpt(), "");
        }
        return defaultText(detailExcerptResolver.apply(item), "");
    }

    private String normalizeText(String content) {
        return content == null ? "" : content.trim();
    }

    private String limit(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "…";
    }

    record DetailSelection(NewsItem item, String clarificationReply) {
        static DetailSelection none() {
            return new DetailSelection(null, null);
        }

        static DetailSelection match(NewsItem item) {
            return new DetailSelection(item, null);
        }

        static DetailSelection clarify(String reply) {
            return new DetailSelection(null, reply);
        }

        boolean matched() {
            return item != null;
        }
    }

    private record RankedNewsCandidate(NewsItem item, PythonMLClient.SemanticRankMatch match) {
    }
}