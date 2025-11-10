package com.example.datalake.mrpot.util;

import java.util.*;
import java.util.regex.*;

public final class CodeFenceUtils {
    private static final Pattern CODE_FENCE = Pattern.compile("```[\\s\\S]*?```");

    public static class Segment {
        public final String text;
        public final boolean isCode;
        public Segment(String t, boolean c){ text=t; isCode=c; }
    }

    public static List<Segment> split(String input) {
        List<Segment> out = new ArrayList<>();
        if (input == null || input.isEmpty()) { out.add(new Segment("", false)); return out; }
        Matcher m = CODE_FENCE.matcher(input); int last = 0;
        while (m.find()) {
            if (m.start() > last) out.add(new Segment(input.substring(last, m.start()), false));
            out.add(new Segment(input.substring(m.start(), m.end()), true));
            last = m.end();
        }
        if (last < input.length()) out.add(new Segment(input.substring(last), false));
        return out;
    }

    public static String join(List<Segment> segs) {
        StringBuilder sb = new StringBuilder();
        for (Segment s : segs) sb.append(s.text);
        return sb.toString();
    }

    private CodeFenceUtils(){}
}
