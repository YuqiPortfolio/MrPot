package com.example.datalake.mrpot.processor;

import com.example.datalake.mrpot.model.Intent;
import com.example.datalake.mrpot.model.ProcessingContext;
import com.example.datalake.mrpot.util.PromptRenderUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Component
public class CommonResponseProcessor implements TextProcessor {

  private static final String NAME = "common-response";

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Mono<ProcessingContext> process(ProcessingContext ctx) {
    String normalized = ctx.getNormalized();
    String base = (normalized == null || normalized.isBlank()) ? ctx.getRawInput() : normalized;
    if (base == null || base.isBlank()) {
      return Mono.just(ctx.addStep(NAME, "no-text"));
    }

    String trimmed = base.trim();
    CommonReply reply = detectReply(ctx, trimmed);
    if (reply == null) {
      return Mono.just(ctx.addStep(NAME, "no-match"));
    }

    ctx.setCommonResponse(true);
    String systemPrompt = PromptRenderUtils.baseSystemPrompt();
    ctx.setSystemPrompt(systemPrompt);
    ctx.setUserPrompt(reply.message());
    ctx.setFinalPrompt(PromptRenderUtils.assembleFinalPrompt(systemPrompt, reply.message()));
    ctx.addStep(NAME, "matched " + reply.language());
    log.debug("Common response matched for language={} text={}", reply.language(), trimmed);
    return Mono.just(ctx);
  }

  private static final Pattern EN_GREETING_PATTERN = Pattern.compile("\\b(hi|hello|hey|greetings|howdy|hola|sup)\\b");
  private static final Set<String> EN_GREETING_PHRASES = Set.of(
      "hi there",
      "hello there",
      "hey there",
      "good morning",
      "good afternoon",
      "good evening",
      "good day",
      "what's up"
  );

  private static final List<String> EN_INTRO_PHRASES = List.of(
      "who are you",
      "what are you",
      "what's your name",
      "what is your name",
      "what can you do",
      "what do you do",
      "what do you offer",
      "introduce yourself",
      "tell me about yourself",
      "what can u do",
      "how can you help",
      "who is mr pot",
      "what are your capabilities",
      "what can you help with",
      "what can you help me with",
      "what service do you provide",
      "can you introduce yourself",
      "can u introduce yourself"
  );

  private static final List<String> EN_NAV_HINTS = List.of(
      "help",
      "where",
      "section",
      "project",
      "projects",
      "blog",
      "blogs",
      "experience",
      "about",
      "portfolio",
      "navigate",
      "navigation",
      "menu",
      "links",
      "contact"
  );

  private CommonReply detectReply(ProcessingContext ctx, String text) {
    String lower = text.toLowerCase(Locale.ROOT);
    Intent intent = ctx.getIntent() == null ? Intent.UNKNOWN : ctx.getIntent();
    boolean containsHan = containsHan(text);
    boolean preferChinese = containsHan || PromptRenderUtils.languageCode(ctx).startsWith("zh");

    if (intent == Intent.GREETING) {
      return preferChinese ? chineseGreeting() : englishGreeting();
    }

    if (preferChinese) {
      if (isChineseGreeting(lower)) {
        return chineseGreeting();
      }
      if (isChineseIntro(lower)) {
        return new CommonReply("zh", "我是 Mr Pot，一个 AI 智能体，可以向你介绍关于我的背景、项目成果、实战经验以及技术博客。告诉我你想了解哪一部分吧！");
      }
      if (isChineseNavigation(lower)) {
        return new CommonReply("zh", "导航：<a href=\"/#about\">关于我</a>｜<a href=\"/#experience\">经历</a>｜<a href=\"/#projects\">项目</a>｜<a href=\"/#blog\">技术博客</a>");
      }
    } else {
      if (isEnglishGreeting(lower)) {
        return englishGreeting();
      }
      if (isEnglishIntro(lower)) {
        return new CommonReply("en", "I'm Mr Pot, an AI agent ready to introduce my background, highlight key projects, share experience insights, and discuss my latest tech blogs. What would you like to explore?");
      }
      if (isEnglishNavigation(lower)) {
        return new CommonReply("en", "Need a hand? Sections → <a href=\"/#about\">About Me</a> | <a href=\"/#projects\">Projects</a> | <a href=\"/#blog\">Tech Blogs</a> | <a href=\"/#experience\">Experience</a>");
      }
    }
    return null;
  }

  private CommonReply englishGreeting() {
    return new CommonReply("en", "Hi there! I'm Mr Pot, your AI agent. I can point you to my background, showcase projects, or chat through blog posts and experience.");
  }

  private CommonReply chineseGreeting() {
    return new CommonReply("zh", "你好！我是 Mr Pot，一个 AI 助手，可以带你了解我的介绍、项目、经历和技术博客。需要我怎么帮你？");
  }

  private boolean isEnglishGreeting(String lower) {
    if (EN_GREETING_PATTERN.matcher(lower).find()) {
      return true;
    }
    return EN_GREETING_PHRASES.stream().anyMatch(phrase -> lower.contains(phrase));
  }

  private boolean isEnglishIntro(String lower) {
    return EN_INTRO_PHRASES.stream().anyMatch(lower::contains);
  }

  private boolean isEnglishNavigation(String lower) {
    if (!lower.contains("where") && !lower.contains("help")) {
      // quick skip if no obvious navigation cue
      boolean hasNavKeyword = EN_NAV_HINTS.stream().anyMatch(lower::contains);
      if (!hasNavKeyword) {
        return false;
      }
    }
    return lower.contains("help") ||
        (lower.contains("where") && EN_NAV_HINTS.stream().anyMatch(lower::contains));
  }

  private boolean isChineseGreeting(String lower) {
    return lower.contains("你好") ||
        lower.contains("您好") ||
        lower.contains("嗨") ||
        lower.contains("早上好") ||
        lower.contains("晚上好") ||
        lower.contains("下午好");
  }

  private boolean isChineseIntro(String lower) {
    return lower.contains("你是谁") ||
        lower.contains("你叫什么") ||
        lower.contains("自我介绍") ||
        lower.contains("介绍一下") ||
        lower.contains("做什么") ||
        lower.contains("能做什么") ||
        lower.contains("可以帮") ||
        lower.contains("能帮") ||
        lower.contains("能做些什么") ||
        lower.contains("帮我做什么") ||
        lower.contains("你是做什么的");
  }

  private boolean isChineseNavigation(String lower) {
    return lower.contains("导航") ||
        lower.contains("帮助") ||
        lower.contains("去哪") ||
        lower.contains("在哪") ||
        lower.contains("哪里") ||
        lower.contains("何处") ||
        lower.contains("怎么找") ||
        lower.contains("怎么去") ||
        lower.contains("目录") ||
        lower.contains("菜单") ||
        lower.contains("链接");
  }

  private boolean containsHan(String text) {
    return text.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
  }

  private record CommonReply(String language, String message) {}
}
