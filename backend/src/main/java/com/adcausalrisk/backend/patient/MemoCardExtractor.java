package com.adcausalrisk.backend.patient;

import com.adcausalrisk.backend.common.BusinessException;
import com.adcausalrisk.backend.common.ErrorCode;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把一段患者备忘拆成「问句 + 答案 + 原文出处」的卡片草稿。
 *
 * <p>旧实现只有三条写死的整句正则（冒号、{@code X 在 Y}、{@code 我 要…}），句式不匹配就退化成
 * 「我记下的这件事是什么？→ 整句原文」，问句永远一样、答案就是原文，卡片等于把原文念一遍。
 * 这里改成按字段抽取：时间、地点、物品、频率、条件、否定各有一个独立抽取器，命中哪个字段就用
 * 对应的模板组装问句，答案只放字段值，不再把整句塞进答案。
 *
 * <p>否定单独成一类，是为了避免患者复习时把「不要空腹吃」记成「空腹吃」——问句必须带着
 * 「要避免做什么」的语义，极性不能被抹掉。
 *
 * <p>抽取顺序是有讲究的：
 * <ol>
 *   <li>冒号与条件句在整句层面先判断，因为它们跨逗号（「如果头晕，就马上坐下休息」一旦按逗号
 *       切开，条件与动作就分家）；
 *   <li>分句后按「提醒 → 否定 → 地点 → 时间 → 频率 → 物品 → 意图」的顺序尝试。提醒排在否定
 *       前面，是因为「别忘记带伞」虽然带否定词，语义却是「要带」；
 *   <li>全都不命中才退回整句兜底。宁可少一张卡，也不拼出答非所问的内容。
 * </ol>
 *
 * <p>{@code sourceRef} 记录命中的原文片段，预览页据此让患者核对卡片来自哪句话。
 *
 * <p>整句兜底有一条硬约束：原话必须完整放进卡片。放不进一张卡时直接拒绝预览并提示患者
 * 改短原文，不做截断——被截掉的句尾常常正是「不要独自离开」这类限制条件。
 */
@Component
class MemoCardExtractor {

    /** 一张卡片草稿。sourceRef 是抽取所依据的原文片段，供患者预览时回看核对。 */
    record Draft(String frontText, String backText, String sourceRef) {}

    static final String WHOLE_SENTENCE_QUESTION = "我记下的这件事是什么？";

    private static final int MAX_CARDS = 6;

    /**
     * 卡片文字上限，与 {@code PatientFlashCard} 的 front_text/back_text 列宽和契约的 maxLength 一致。
     *
     * <p>整句兜底最多保留到这里，再长也不能靠截断收场：句尾往往是「…不要独自离开」这类限制条件，
     * 截掉它等于悄悄改写医嘱，而卡片上没有任何痕迹说明自己被截断过。
     */
    private static final int MAX_CARD_TEXT = 500;

    private static final int MAX_SOURCE = 200;

    /** 语义片段最长上限：再长就不像一个字段值，多半是模型把整句圈了进来。 */
    private static final int MAX_SPAN = 40;

    /** 分句：句号、问号、叹号、分号、换行。顿号不切，它是物品清单里的连接符。 */
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("[。！？!?;；\\n]+");

    /** 分句内部的逗号切分，用于一句多事项。 */
    private static final Pattern CLAUSE_SPLIT = Pattern.compile("[，,]+");

    /**
     * 冒号结构「标题：内容」。放在整句层面判断，优先于任何句式推断。
     * 「16:00」「http://…」这类冒号不是问答结构，需要排除。
     */
    private static final Pattern COLON = Pattern.compile("^(.{2,120})[:：](.{1,120})$");

    /**
     * 条件句「如果/要是/一旦/假如/万一/若 X 就/则 Y」。跨逗号，必须在分句之前处理。
     *
     * <p>「万一」「若」与前面四个线索词同义，只是更口语（「万一发烧就吃退烧药」）。
     * 它们不在整句正则里时，这种句子只能落到条件兜底，条件与动作都抽不出来；
     * 放进正则后条件与动作各自归位，卡片是「发烧时该怎么做？→ 吃退烧药」。
     */
    private static final Pattern CONDITION =
            Pattern.compile("^(?:如果|要是|一旦|假如|万一|若)(.{1,40}?)(?:就|则|那就)(.{1,40})$");

    /**
     * 带逗号但不带「就/则」的条件句：「如果下雨，不要去公园」。
     *
     * <p>按逗号切开会把条件和动作分家：「如果下雨」自己抽不出字段只能整句兜底，
     * 「不要去公园」则被当成独立的否定句，问句变成无条件的「我要避免做什么？」——
     * 患者复习时会把「下雨才别去」记成「永远别去」，原意被改写。
     * 所以条件与动作必须在整句层面一起处理。
     */
    private static final Pattern CONDITION_CLAUSE =
            Pattern.compile("^(?:如果|要是|一旦|假如|万一|若)(.{1,40}?)[，,](.{1,60})$");

    /**
     * 条件放在前面、用「的话」收尾的句子：「下雨的话，不要去公园」。
     *
     * <p>「的话」后面必须跟逗号或「就/则/那就」才算条件句。只跟一句评价的
     * （「带伞的话更好」）拆开只会得到一条没有意义的问答，仍按整句兜底处理。
     */
    private static final Pattern CONDITION_TAIL =
            Pattern.compile("^(.{1,40}?)(?:的话)(?:[，,](?:那就|就|则)?|(?:那就|就|则))(.{1,60})$");

    /**
     * 条件线索词。整句只要带这些词，就说明它在讲「什么情况下做什么」，
     * 不能按逗号拆成两半——否则条件那一半只剩整句兜底，动作那一半变成无条件的嘱咐。
     */
    private static final Pattern CONDITION_CUE = Pattern.compile("如果|要是|一旦|假如|万一|若|的话");

    /** 提醒句：「别忘记带伞」「记得带医保卡」语义是「要带」，不是否定。 */
    private static final Pattern REMINDER =
            Pattern.compile("^(?:别(?:忘记|忘了)|不要忘记|记得|记住)(?:要)?(.{1,40})$");

    /** 否定句：「不要空腹吃这个药」「忌辛辣」。 */
    private static final Pattern NEGATION =
            Pattern.compile("^(?:不要|别|不能|不可以|禁止|避免|忌)(?:再)?(.{1,40})$");

    /**
     * 地点句：「血压计放在客厅电视柜下面」「钥匙搁在玄关柜上」。
     * 旧实现只认「在」加后缀枚举（里面/里/中/上/下/处/旁边/内），「放在」这种把动词
     * 和「在」连写的常见写法会漏抽，所以这里把动词与「在」一起作为分隔点。
     */
    private static final Pattern PLACE =
            Pattern.compile("^(.{1,40}?)(放在|摆在|搁在|收在|藏在|停在|挂在|贴在|存放在|在)(.{1,40})$");

    /**
     * 时间前缀：「下周三上午九点去社区医院抽血」「明天上午去药房取药」。
     * 命中后剩下的部分是动作，问句用「什么时候…？」，答案只放时间。
     *
     * <p>日期后面允许单独跟一个时段（上午/下午/晚上），这是口语里最常见的写法；
     * 但只有日期、没有时段也没有钟点时（「今天」「明天」），仍然算时间表达。
     */
    private static final Pattern TIME_PREFIX =
            Pattern.compile(
                    "^("
                            + "(?:(?:下下|下|本|这|上)(?:周|星期|礼拜)[一二三四五六日天]?"
                            + "|(?:今|明|后|昨)(?:天|日)"
                            + "|[0-9]{1,2}月[0-9]{1,2}(?:日|号)?"
                            + ")"
                            + "(?:上午|下午|晚上|早上|中午|凌晨)?"
                            + "(?:(?:[0-9]{1,2}|[一二三四五六七八九十]{1,3})(?:点|时)"
                            + "(?:(?:[0-9]{1,2}|[一二三四五六七八九十]{1,3})分|[半整]|之前|以前|左右)?"
                            + "|[0-9]{1,2}:[0-9]{2}"
                            + ")?"
                            + ")(.{2,80})");

    /** 频率句：「每天早晚各量一次血压」「每周三复查」。 */
    private static final Pattern FREQUENCY =
            Pattern.compile(
                    "^((?:每天|每日|每周|每月|每年|每晚|每早|每晨|每次|一日|一天|一周)"
                            + "[一二三四五六日天]?(?:[早晚中午各]{0,4})?)([量测吃服做查记].{1,30})$");

    /** 频率句里的量词，抽出来挪进答案，动作部分保持干净。 */
    private static final Pattern QUANTIFIER = Pattern.compile("(一次|两次|三次|一回|一遍|一趟)");

    /** 物品句：「复诊要带医保卡、病历本和化验单」。主语可省略，所以是 {@code .{0,12}?}。 */
    private static final Pattern ITEM =
            Pattern.compile("^(.{0,12}?)(要带|需要带|得带|记得带|要拿|需要拿|带|拿|取)(.{2,60})$");

    /** 附带事项：「顺便把血压记录下来」。放在时间/物品之后，避免抢走「明天上午去药房取药」。 */
    private static final Pattern ADDITIONAL = Pattern.compile("^(?:顺便|另外|还要|还有)(.{2,40})$");

    /** 意图句：「我要每天吃降压药」。保留原有句式，保证旧调用方行为不变。 */
    private static final Pattern INTENT = Pattern.compile("^我(?:要|需要|得|会|打算|计划|想)(.{1,80})$");

    /** 疑问词：句子本身在提问时，不能再拿它当陈述句去拆答案。 */
    private static final Set<String> QUESTION_WORDS = Set.of("哪", "什么", "谁", "怎么", "多少");

    /**
     * 动作标记。时间句只有在时间后面真的跟着动作时才算一条「提醒」，
     * 否则「今天天气不错」这种顺口一说的句子会被拆成「什么时候天气不错？」——
     * 那种卡片的问句比整句兜底还难答。宁可退回整句，也不制造无意义的问答。
     */
    private static final Set<String> ACTION_MARKERS =
            Set.of("去", "到", "回", "来", "见", "吃", "喝", "服", "量", "测", "取", "买", "交",
                    "打", "做", "查", "记", "带", "拿", "洗", "换", "约", "问", "找", "跑",
                    "开", "停", "睡", "复诊", "挂号", "抽血", "检查", "看", "听", "练", "办", "签");

    /**
     * 标签与句子表面线索必须一致。
     *
     * <p>实测 uie-nano 会把「明天上午去药房取药，顺便把血压记录下来」里的「血压」标成
     * CONDITION。这类片段能过「在原文里、不是疑问词、长度合规」三道检查，却会把卡片写成
     * 「什么情况下要这样做？→ 血压」——答案本身不带任何条件线索，患者一眼也看不出配错。
     * 所以条件和否定这两种标签额外要求原句真的带对应线索词；时间和地点这类答案自带线索，
     * 不在这里限制。
     */
    private static final Map<String, Pattern> LABEL_CUES =
            Map.of(
                    "CONDITION", Pattern.compile("如果|要是|一旦|假如|万一|若"),
                    "NEGATION", Pattern.compile("不要|别|不能|不可以|禁止|避免|忌"),
                    "FREQUENCY", Pattern.compile("每"));

    private final MemoSemanticClient semantic;

    MemoCardExtractor(MemoSemanticClient semantic) {
        this.semantic = semantic;
    }

    List<Draft> extract(String content) {
        List<Draft> results = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return results;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String rawSentence : SENTENCE_SPLIT.split(content)) {
            String sentence = rawSentence.trim();
            if (sentence.length() < 2) {
                continue;
            }
            if (addIfNew(results, seen, fromColon(sentence))) {
                if (results.size() >= MAX_CARDS) break;
                continue;
            }
            if (addIfNew(results, seen, fromCondition(sentence))) {
                if (results.size() >= MAX_CARDS) break;
                continue;
            }
            if (addIfNew(results, seen, fromConditionClause(sentence))) {
                if (results.size() >= MAX_CARDS) break;
                continue;
            }
            if (hasConditionCue(sentence)) {
                // 拆不出「条件 + 动作」时整句保留：宁可卡片笨一点，
                // 也不能把「下雨的话」和「不要去公园」拆成两张、让条件消失。
                if (addIfNew(results, seen, fromConditionOnly(sentence))) {
                    if (results.size() >= MAX_CARDS) break;
                }
                continue;
            }
            for (String rawClause : CLAUSE_SPLIT.split(sentence)) {
                String clause = rawClause.trim();
                if (clause.length() < 2) {
                    continue;
                }
                if (addIfNew(results, seen, fromClause(clause))) {
                    if (results.size() >= MAX_CARDS) break;
                }
            }
            if (results.size() >= MAX_CARDS) break;
        }

        if (results.isEmpty()) {
            String trimmed = content.trim();
            if (!trimmed.isBlank()) {
                requireCardFits(trimmed);
                results.add(new Draft(WHOLE_SENTENCE_QUESTION, trimmed, cap(trimmed, MAX_SOURCE)));
            }
        }
        return results;
    }

    private boolean addIfNew(List<Draft> results, Set<String> seen, Draft draft) {
        if (draft == null) return false;
        // 上限只认 MAX_CARD_TEXT，避免这里和 requireCardFits 各写一个数字日后漂移。
        if (draft.frontText().length() > MAX_CARD_TEXT || draft.backText().length() > MAX_CARD_TEXT) {
            return false;
        }
        String key = draft.frontText() + '\u0000' + draft.backText();
        if (!seen.add(key)) return false;
        results.add(draft);
        return true;
    }

    private Draft fromColon(String sentence) {
        Matcher m = COLON.matcher(sentence);
        if (!m.matches()) return null;
        String head = m.group(1).trim();
        String tail = m.group(2).trim();
        if (head.length() < 2 || tail.isBlank()) return null;
        // 「16:00」「http://…」不是问答结构。
        if (Character.isDigit(head.charAt(head.length() - 1)) && Character.isDigit(tail.charAt(0))) return null;
        if (tail.startsWith("//")) return null;
        String question = head.endsWith("？") || head.endsWith("?") ? head : head + "？";
        return new Draft(question, tail, cap(sentence, MAX_SOURCE));
    }

    private Draft fromCondition(String sentence) {
        if (containsQuestionWord(sentence)) return null;
        Matcher m = CONDITION.matcher(sentence);
        if (!m.matches()) return null;
        String condition = trimPunctuation(m.group(1));
        String action = trimPunctuation(m.group(2));
        if (condition.isBlank() || action.isBlank()) return null;
        if (containsQuestionWord(action)) return null;
        return new Draft(condition + "时该怎么做？", action, cap(sentence, MAX_SOURCE));
    }

    /**
     * 「如果下雨，不要去公园」这种没有「就/则」的条件句。答案保留否定/提醒的原样措辞，
     * 问句带上条件，患者复习时才不会把有条件的嘱咐记成无条件。
     *
     * <p>只检查动作部分有没有疑问词：条件里出现「哪里」「什么」是口语里的不定指
     * （「如果哪里不舒服，就告诉我」），不是提问，整句丢掉反而会把条件与动作拆散。
     */
    private Draft fromConditionClause(String sentence) {
        Matcher m = CONDITION_CLAUSE.matcher(sentence);
        String condition;
        String action;
        if (m.matches()) {
            condition = trimPunctuation(m.group(1));
            action = trimPunctuation(m.group(2));
        } else {
            m = CONDITION_TAIL.matcher(sentence);
            if (!m.matches()) return null;
            condition = trimPunctuation(m.group(1));
            action = trimPunctuation(m.group(2));
        }
        // 「如果下雨的话，…」与「下雨的话，…」里的「的话」只是条件标记，
        // 留在问句里会读成「下雨的话时该怎么做？」，去掉才是自然的口语问法。
        condition = trimPunctuation(condition.replaceFirst("的话$", ""));
        if (condition.isBlank() || action.isBlank()) return null;
        if (containsQuestionWord(action)) return null;
        // 只去掉「那就/就/则」这类纯连接词。裸「那」不能去：它常常是指示代词的一部分，
        // 「下雨的话，那件外套要带上」的答案一旦去掉「那」就变成「件外套要带上」。
        action = trimPunctuation(action.replaceFirst("^(?:那就|就|则)", ""));
        if (action.isBlank()) return null;
        return new Draft(condition + "时该怎么做？", action, cap(sentence, MAX_SOURCE));
    }

    private boolean hasConditionCue(String sentence) {
        return CONDITION_CUE.matcher(sentence).find();
    }

    /**
     * 条件句拆不出「条件 + 动作」时的兜底：整句作为一张卡片，条件和动作一起保留。
     *
     * <p>这里刻意不交给语义侧。语义侧给出的永远只是原句里的一个片段，拿它当答案必然是
     * 一次不完整的拆分：标成否定的片段只剩动作（「如果下雨不要去公园」会变成无条件的
     * 「我要避免做什么？→ 去公园」），标成条件的片段只剩条件、动作彻底消失。
     * 既然拆不出完整的一对，就整句保留——患者看到的是「如果下雨不要去公园」，
     * 而不是一条被改写过的、丢了条件的嘱咐。
     */
    private Draft fromConditionOnly(String sentence) {
        String trimmed = trimPunctuation(sentence);
        if (trimmed.isBlank()) return null;
        requireCardFits(trimmed);
        return new Draft(WHOLE_SENTENCE_QUESTION, trimmed, cap(sentence, MAX_SOURCE));
    }

    /**
     * 单个分句的抽取。规则命中时直接返回；规则全部落空时，交给语义侧补抽
     * （{@link MemoSemanticClient}），语义侧也不可用才退回整句兜底。
     */
    private Draft fromClause(String clause) {
        Draft ruled = fromRule(clause);
        if (ruled != null) return ruled;
        for (Draft drafted : fromSemantic(clause)) {
            return drafted;
        }
        return fallback(clause);
    }

    private Draft fromRule(String clause) {
        Draft reminder = fromReminder(clause);
        if (reminder != null) return reminder;
        Draft negation = fromNegation(clause);
        if (negation != null) return negation;
        Draft place = fromPlace(clause);
        if (place != null) return place;
        Draft time = fromTime(clause);
        if (time != null) return time;
        Draft frequency = fromFrequency(clause);
        if (frequency != null) return frequency;
        Draft item = fromItem(clause);
        if (item != null) return item;
        Draft additional = fromAdditional(clause);
        if (additional != null) return additional;
        return fromIntent(clause);
    }

    private Draft fromReminder(String clause) {
        Matcher m = REMINDER.matcher(clause);
        if (!m.matches()) return null;
        String action = m.group(1).trim();
        if (action.isBlank() || containsQuestionWord(action)) return null;
        return new Draft("要记得做什么？", action, cap(clause, MAX_SOURCE));
    }

    private Draft fromNegation(String clause) {
        Matcher m = NEGATION.matcher(clause);
        if (!m.matches()) return null;
        String action = m.group(1).trim();
        if (action.isBlank() || containsQuestionWord(action)) return null;
        // 问句必须带否定语义，否则「不要空腹吃」会被复习成「空腹吃」。
        return new Draft("我要避免做什么？", action, cap(clause, MAX_SOURCE));
    }

    private Draft fromPlace(String clause) {
        if (containsQuestionWord(clause)) return null;
        Matcher m = PLACE.matcher(clause);
        if (!m.matches()) return null;
        String subject = m.group(1).trim();
        String verb = m.group(2);
        String location = m.group(3).trim();
        if (subject.isBlank() || location.isBlank()) return null;
        if (containsQuestionWord(subject) || containsQuestionWord(location)) return null;
        // 问句保留动词，读起来才像人话：「钥匙搁在哪里？」而不是「钥匙在哪里？」。
        // 动词本身已经以「在」结尾时（放在/搁在/挂在），去掉这个「在」，避免「放在在哪里」。
        String stem = verb.endsWith("在")
                ? subject + verb.substring(0, verb.length() - 1)
                : subject + verb;
        return new Draft(stem + "在哪里？", location, cap(clause, MAX_SOURCE));
    }

    private Draft fromTime(String clause) {
        Matcher m = TIME_PREFIX.matcher(clause);
        if (!m.matches()) return null;
        String when = m.group(1).trim();
        String action = m.group(2).trim();
        if (when.isBlank() || action.isBlank()) return null;
        if (containsQuestionWord(action)) return null;
        if (!containsActionMarker(action)) return null;
        return new Draft("什么时候" + action + "？", when, cap(clause, MAX_SOURCE));
    }

    private Draft fromFrequency(String clause) {
        Matcher m = FREQUENCY.matcher(clause);
        if (!m.matches()) return null;
        String every = m.group(1).trim();
        String action = m.group(2).trim();
        if (every.isBlank() || action.isBlank()) return null;

        Matcher quantifier = QUANTIFIER.matcher(action);
        if (quantifier.find()) {
            String times = quantifier.group(1);
            String bareAction = (action.substring(0, quantifier.start()) + action.substring(quantifier.end())).trim();
            if (!bareAction.isBlank()) {
                return new Draft(bareAction + "的频率是什么？", every + times, cap(clause, MAX_SOURCE));
            }
        }
        return new Draft("这件事多久做一次？", every, cap(clause, MAX_SOURCE));
    }

    private Draft fromItem(String clause) {
        Matcher m = ITEM.matcher(clause);
        if (!m.matches()) return null;
        String subject = m.group(1).trim();
        String verb = m.group(2);
        String items = m.group(3).trim();
        if (items.isBlank() || containsQuestionWord(items)) return null;
        return new Draft(subject + verb + "什么？", items, cap(clause, MAX_SOURCE));
    }

    private Draft fromAdditional(String clause) {
        Matcher m = ADDITIONAL.matcher(clause);
        if (!m.matches()) return null;
        String action = m.group(1).trim();
        if (action.isBlank() || containsQuestionWord(action)) return null;
        return new Draft("另外还要做什么？", action, cap(clause, MAX_SOURCE));
    }

    private Draft fromIntent(String clause) {
        Matcher m = INTENT.matcher(clause);
        if (!m.matches()) return null;
        String action = m.group(1).trim();
        if (action.isBlank() || containsQuestionWord(action)) return null;
        return new Draft("我接下来要做什么？", action, cap(clause, MAX_SOURCE));
    }

    /**
     * 语义侧只补规则漏掉的句子，不覆盖规则已经抽对的结果。
     * 这样即使模型把字段配错，最坏也只是少一张卡，不会把已经正确的问答改坏。
     *
     * <p>实测 uie-nano 会把疑问句里的「哪里」当成地点抽出来，卡片就变成
     * 「地点在哪里？→ 哪里」。所以语义片段也要过三道与规则相同的检查：
     * 片段必须真的出现在原句里、不能是疑问词、不能长到不像一个字段值。
     * 宁可退回整句兜底，也不接受一条患者一读就知道配错的事实。
     */
    private List<Draft> fromSemantic(String clause) {
        if (semantic == null) return List.of();
        List<MemoSemanticClient.Span> spans = semantic.spans(clause);
        if (spans.isEmpty()) return List.of();
        List<Draft> drafts = new ArrayList<>();
        for (MemoSemanticClient.Span span : spans) {
            String template = templateFor(span.label());
            if (template == null) continue;
            String answer = span.text().trim();
            if (answer.isBlank() || answer.length() > MAX_SPAN) continue;
            if (!clause.contains(answer)) continue;
            if (containsQuestionWord(answer)) continue;
            if (!cueMatches(span.label(), clause)) continue;
            drafts.add(new Draft(template, answer, cap(clause, MAX_SOURCE)));
        }
        return drafts;
    }

    /** 标签线索不匹配时，宁可不出这张卡，也不写一条患者看不出错的事实。 */
    private static boolean cueMatches(String label, String clause) {
        Pattern cue = LABEL_CUES.get(label);
        return cue == null || cue.matcher(clause).find();
    }

    private static String templateFor(String label) {
        if (label == null) return null;
        return switch (label) {
            case "TIME" -> "这件事什么时候要做？";
            case "PLACE" -> "地点在哪里？";
            case "ITEM" -> "要带哪些东西？";
            case "FREQUENCY" -> "这件事多久做一次？";
            case "CONDITION" -> "什么情况下要这样做？";
            case "NEGATION" -> "我要避免做什么？";
            default -> null;
        };
    }

    private Draft fallback(String clause) {
        requireCardFits(clause);
        return new Draft(WHOLE_SENTENCE_QUESTION, clause, cap(clause, MAX_SOURCE));
    }

    /**
     * 整句兜底必须把原话完整放进卡片，放不下时明确拒绝，绝不截断。
     *
     * <p>这里曾经用 120 字上限截断，患者的 144 字备忘在预览里只剩前半句，句尾的
     * 「不要独自离开」直接消失，而卡片上看不出任何被截断的痕迹——复习时读到的是一条
     * 改了含义的医嘱。放不进一张卡时唯一安全的做法是让患者自己改短原文再预览。
     */
    private static void requireCardFits(String sentence) {
        if (sentence.length() > MAX_CARD_TEXT) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "这句话有" + sentence.length() + "字，超过单张卡片" + MAX_CARD_TEXT
                            + "字上限，请拆成几句或缩短后重新预览");
        }
    }

    private static boolean containsQuestionWord(String text) {
        for (String word : QUESTION_WORDS) {
            if (text.contains(word)) return true;
        }
        return false;
    }

    private static boolean containsActionMarker(String text) {
        for (String marker : ACTION_MARKERS) {
            if (text.contains(marker)) return true;
        }
        return false;
    }

    private static String cap(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** 条件与动作取自同一句时，前后可能残留逗号，拼进问句会变成「下雨，时该怎么做？」。 */
    private static String trimPunctuation(String text) {
        return text == null ? "" : text.replaceAll("^[，,、：:\\s]+|[，,、：:\\s]+$", "");
    }
}