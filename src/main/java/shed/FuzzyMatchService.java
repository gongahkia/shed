package shed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
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
        String lowerQuery = query.toLowerCase();
        String lowerCandidate = candidate.toLowerCase();

        if (lowerCandidate.equals(lowerQuery)) return 10000;
        if (lowerCandidate.startsWith(lowerQuery)) return 5000 + (1000 - candidate.length());

        int qi = 0;
        int score = 0;
        int consecutiveBonus = 0;
        boolean lastMatched = false;

        for (int ci = 0; ci < lowerCandidate.length() && qi < lowerQuery.length(); ci++) {
            if (lowerCandidate.charAt(ci) == lowerQuery.charAt(qi)) {
                qi++;
                score += 100;
                if (lastMatched) {
                    consecutiveBonus += 50;
                    score += consecutiveBonus;
                } else {
                    consecutiveBonus = 0;
                }
                if (ci == 0 || !Character.isLetterOrDigit(lowerCandidate.charAt(ci - 1))) {
                    score += 75;
                }
                lastMatched = true;
            } else {
                lastMatched = false;
                consecutiveBonus = 0;
            }
        }

        if (qi < lowerQuery.length()) return 0;

        score -= candidate.length();
        return Math.max(1, score);
    }

    public List<Match> match(String query, List<String> candidates, int maxResults) {
        if (query == null || candidates == null) return Collections.emptyList();
        if (maxResults > 0) {
            record Ranked(Match match, int index) { }
            PriorityQueue<Ranked> best = new PriorityQueue<>((left, right) -> {
                int byScore = Integer.compare(left.match().score, right.match().score);
                return byScore != 0 ? byScore : Integer.compare(right.index(), left.index());
            });
            for (int index = 0; index < candidates.size(); index++) {
                String candidate = candidates.get(index);
                int candidateScore = score(query, candidate);
                if (candidateScore <= 0) continue;
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
            int s = score(query, candidate);
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
}
