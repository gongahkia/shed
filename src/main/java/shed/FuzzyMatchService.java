package shed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

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
