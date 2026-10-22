package shed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;

public class FuzzyMatchService {

    public static class Match {
        public final String text;
        public final int score;
        public Match(String text, int score) {
            this.text = text;
            this.score = score;
        }
    }

    public int score(String query, String candidate) {
        if (query == null || candidate == null) return 0;
        if (query.isEmpty()) return 1;
        return scoreLower(query.toLowerCase(Locale.ROOT), candidate.toLowerCase(Locale.ROOT), 0);
    }

    private int scoreLower(String lowerQuery, String lowerCandidate, int start) {
        int length = lowerCandidate.length() - start;
        if (lowerQuery.isEmpty()) return 1;
        if (length >= lowerQuery.length() && lowerCandidate.regionMatches(start, lowerQuery, 0, lowerQuery.length()))
            return length == lowerQuery.length() ? 10000 : 5000 + (1000 - length);

        int qi = 0;
        int score = 0;
        int consecutiveBonus = 0;
        boolean lastMatched = false;

        for (int ci = start; ci < lowerCandidate.length() && qi < lowerQuery.length(); ci++) {
            if (lowerCandidate.charAt(ci) == lowerQuery.charAt(qi)) {
                qi++;
                score += 100;
                if (lastMatched) {
                    consecutiveBonus += 50;
                    score += consecutiveBonus;
                } else {
                    consecutiveBonus = 0;
                }
                if (ci == start || !Character.isLetterOrDigit(lowerCandidate.charAt(ci - 1))) {
                    score += 75;
                }
                lastMatched = true;
            } else {
                lastMatched = false;
                consecutiveBonus = 0;
            }
        }

        if (qi < lowerQuery.length()) return 0;

        score -= length;
        return Math.max(1, score);
    }

    public List<Match> match(String query, List<String> candidates, int maxResults) {
        return match(query, candidates, maxResults, false);
    }

    private int scoreCandidate(String query, String candidate, boolean paths) {
        if (candidate == null) return 0;
        String lower = candidate.toLowerCase(Locale.ROOT);
        int pathScore = scoreLower(query, lower, 0);
        if (!paths || query.isEmpty() || query.indexOf('/') >= 0 || query.indexOf('\\') >= 0) return pathScore;
        int start = Math.max(lower.lastIndexOf('/'), lower.lastIndexOf('\\')) + 1;
        int nameScore = scoreLower(query, lower, start);
        return nameScore == 0 ? pathScore : Math.max(pathScore, nameScore + 500 - Math.min(400, start));
    }

    private List<Match> match(String query, List<String> candidates, int maxResults, boolean paths) {
        if (query == null || candidates == null) return Collections.emptyList();
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        if (maxResults > 0) {
            record Ranked(Match match, int index) { }
            PriorityQueue<Ranked> best = new PriorityQueue<>((left, right) -> {
                int byScore = Integer.compare(left.match().score, right.match().score);
                return byScore != 0 ? byScore : Integer.compare(right.index(), left.index());
            });
            for (int index = 0; index < candidates.size(); index++) {
                if (Thread.currentThread().isInterrupted()) return List.of();
                String candidate = candidates.get(index);
                int candidateScore = scoreCandidate(lowerQuery, candidate, paths);
                if (candidateScore <= 0 || best.size() == maxResults && candidateScore <= best.peek().match().score) continue;
                Ranked ranked = new Ranked(new Match(candidate, candidateScore), index);
                if (best.size() < maxResults) best.add(ranked);
                else if (best.comparator().compare(ranked, best.peek()) > 0) {
                    best.remove();
                    best.add(ranked);
                }
            }
            List<Ranked> ranked = new ArrayList<>(best);
            ranked.sort((left, right) -> {
                int byScore = Integer.compare(right.match().score, left.match().score);
                return byScore != 0 ? byScore : Integer.compare(left.index(), right.index());
            });
            return ranked.stream().map(Ranked::match).toList();
        }
        List<Match> matches = new ArrayList<>();
        for (String candidate : candidates) {
            if (Thread.currentThread().isInterrupted()) return List.of();
            int s = scoreCandidate(lowerQuery, candidate, paths);
            if (s > 0) {
                matches.add(new Match(candidate, s));
            }
        }
        matches.sort((a, b) -> Integer.compare(b.score, a.score));
        if (maxResults > 0 && matches.size() > maxResults) {
            return matches.subList(0, maxResults);
        }
        return matches;
    }

    public List<String> matchStrings(String query, List<String> candidates, int maxResults) {
        List<Match> matches = match(query, candidates, maxResults);
        List<String> result = new ArrayList<>(matches.size());
        for (Match m : matches) {
            result.add(m.text);
        }
        return result;
    }

    public List<String> matchPathStrings(String query, List<String> candidates, int maxResults) {
        return match(query, candidates, maxResults, true).stream().map(match -> match.text).toList();
    }
}
