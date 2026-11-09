package app.buddy.assistant;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The voice assistant's offline commands: rules and patterns for Russian and English, no neural network. Returns a
 * Command, or null when it isn't one of them (then the agent gets it, when there's a network). Plain Java, no Android,
 * so it's covered by unit tests (CommandParserTest).
 */
final class CommandParser {
    private CommandParser() {
    }

    private static final java.util.Map<String, Pattern> PATTERNS = new java.util.concurrent.ConcurrentHashMap<>();

    /** A pattern where \\w and \\b know Cyrillic (Java's don't by default). */
    private static Pattern p(String regex) {
        Pattern c = PATTERNS.get(regex);
        if (c == null) {
            c = Pattern.compile(regex, Pattern.UNICODE_CHARACTER_CLASS);
            PATTERNS.put(regex, c);
        }
        return c;
    }

    private static boolean is(String s, String regex) {
        return p(regex).matcher(s).matches();
    }

    static Command parse(String raw, String lang) {
        if (raw == null) return null;
        String s = normalize(raw);
        if (s.isEmpty()) return null;
        if (s.matches(".*[\\u0600-\\u06FF].*")) return null; // Arabic: the agent understands it
        boolean ru = s.matches(".*[а-я].*") || (lang != null && lang.startsWith("ru") && !s.matches(".*[a-z].*"));
        Command c = ru ? ru(s) : en(s);
        if (c == null) c = ru ? en(s) : ru(s); // a recognizer that wrote the other language
        return c;
    }

    // --------------------------------------------------------- normalizing

    /** Lower case, ё as е, no punctuation but : % +, number words as digits, single spaces. */
    static String normalize(String raw) {
        String s = raw.toLowerCase(Locale.ROOT).replace('ё', 'е');
        s = s.replaceAll("[,!?;«»\"()\\[\\]]", " ").replaceAll("\\.(?!\\d)", " ").replaceAll("\\s+", " ").trim();
        s = s.replace("полтора часа", "90 минут").replace("полчаса", "30 минут").replace("half an hour", "30 minutes")
                .replace("an hour and a half", "90 minutes");
        return numbers(s);
    }

    private static final String[][] RU_NUM = {
            {"ноль", "0"}, {"один", "1"}, {"одна", "1"}, {"одну", "1"}, {"одно", "1"}, {"два", "2"}, {"две", "2"},
            {"три", "3"}, {"четыре", "4"}, {"пять", "5"}, {"шесть", "6"}, {"семь", "7"}, {"восемь", "8"},
            {"девять", "9"}, {"десять", "10"}, {"одиннадцать", "11"}, {"двенадцать", "12"}, {"тринадцать", "13"},
            {"четырнадцать", "14"}, {"пятнадцать", "15"}, {"шестнадцать", "16"}, {"семнадцать", "17"},
            {"восемнадцать", "18"}, {"девятнадцать", "19"}, {"двадцать", "20"}, {"тридцать", "30"}, {"сорок", "40"},
            {"пятьдесят", "50"}, {"шестьдесят", "60"}, {"семьдесят", "70"}, {"восемьдесят", "80"},
            {"девяносто", "90"}, {"сто", "100"},
            {"zero", "0"}, {"one", "1"}, {"two", "2"}, {"three", "3"}, {"four", "4"}, {"five", "5"}, {"six", "6"},
            {"seven", "7"}, {"eight", "8"}, {"nine", "9"}, {"ten", "10"}, {"eleven", "11"}, {"twelve", "12"},
            {"thirteen", "13"}, {"fourteen", "14"}, {"fifteen", "15"}, {"sixteen", "16"}, {"seventeen", "17"},
            {"eighteen", "18"}, {"nineteen", "19"}, {"twenty", "20"}, {"thirty", "30"}, {"forty", "40"},
            {"fifty", "50"}, {"sixty", "60"}, {"seventy", "70"}, {"eighty", "80"}, {"ninety", "90"}, {"hundred", "100"},
    };

    private static int word(String w) {
        for (String[] n : RU_NUM) if (n[0].equals(w)) return Integer.parseInt(n[1]);
        return -1;
    }

    /** "двадцать пять" → "25", "twenty-five" → "25": a run of number words becomes one number. */
    private static String numbers(String s) {
        s = s.replaceAll("\\b(twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)-(one|two|three|four|five|six|seven|eight|nine)\\b",
                "$1 $2");
        String[] t = s.split(" ");
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < t.length) {
            int v = word(t[i]);
            if (v < 0) {
                out.append(t[i++]).append(' ');
                continue;
            }
            int total = v;
            i++;
            while (i < t.length) { // tens + units (двадцать пять), hundred + the rest (сто двадцать)
                int w = word(t[i]);
                boolean joins = w >= 0 && ((total % 100 == 0 && total >= 100 && w < 100)
                        || (total >= 20 && total < 100 && total % 10 == 0 && w > 0 && w < 10));
                if (!joins) break;
                total += w;
                i++;
            }
            out.append(total).append(' ');
        }
        return out.toString().trim();
    }

    // ------------------------------------------------------- time, lengths

    private static final Pattern CLOCK = Pattern.compile("(?U)^(?:в|на|at|for)?\\s*(\\d{1,2})(?:\\s*[:.h]\\s*(\\d{2})"
            + "|\\s+(?:час\\w*\\s+)?(\\d{1,2})(?:\\s+минут\\w*)?|\\s+час\\w*)?\\s*(утра|вечера|дня|ночи|am|pm|a m|p m)?"
            + "(?:\\s+o'?clock)?$");

    /** "7", "7:30", "7 30", "в 7 часов 30 минут вечера", "7 pm" → {hour, minute}; null if not a time. */
    static int[] clock(String t) {
        Matcher m = CLOCK.matcher(t.trim());
        if (!m.matches()) return null;
        int h = Integer.parseInt(m.group(1));
        String mm = m.group(2) != null ? m.group(2) : m.group(3);
        int min = mm == null ? 0 : Integer.parseInt(mm);
        String part = m.group(4);
        if (part != null) {
            boolean pm = part.equals("вечера") || part.equals("дня") || part.startsWith("p");
            boolean night = part.equals("ночи") || part.startsWith("a") || part.equals("утра");
            if (pm && h < 12) h += 12;
            if (night && h == 12) h = 0;
        }
        if (h > 23 || min > 59) return null;
        return new int[]{h, min};
    }

    private static final String UNIT = "(час(?:а|ов)?|ч|минут[уаы]?|мин|секунд[уаы]?|сек|hours?|hrs?|minutes?|mins?|seconds?|secs?)";
    private static final Pattern LENGTH_PART = Pattern.compile("(?U)(?:(\\d+|an?)\\s*)?" + UNIT + "\\b");
    private static final String LENGTH = "(?:(?:\\d+|an?)\\s*)?" + UNIT + "(?:\\s+(?:и\\s+|and\\s+)?(?:(?:\\d+|an?)\\s*)?" + UNIT + ")*";

    /** "5 минут", "1 час 30 минут", "минуту", "an hour" → seconds; -1 if the whole of t isn't a length. */
    static long length(String t) {
        t = t.trim();
        if (!is(t, LENGTH)) return -1;
        long total = 0;
        Matcher m = LENGTH_PART.matcher(t);
        while (m.find()) {
            String n = m.group(1);
            long v = n == null || n.startsWith("a") ? 1 : Long.parseLong(n);
            String u = m.group(2);
            if (u.startsWith("ч") || u.startsWith("h")) total += v * 3600;
            else if (u.startsWith("м") || u.startsWith("mi")) total += v * 60;
            else total += v;
        }
        return total > 0 ? total : -1;
    }

    private static final java.util.Set<String> PRONOUNS = new java.util.HashSet<>(java.util.Arrays.asList(
            "ему", "ей", "им", "его", "ее", "него", "нее", "ней", "нему", "этому", "тому", "туда", "ему же",
            "him", "her", "them", "it", "that", "there", "this"));

    /** A target that only makes sense after an earlier message (отправь ему…): the agent's job. */
    private static boolean pronoun(String target) {
        return target == null || PRONOUNS.contains(target.trim());
    }

    private static boolean looksLikeNumber(String t) {
        return t.replaceAll("[^0-9]", "").length() >= 3 && t.matches("[+0-9 ()\\-]+");
    }

    private static Command target(Command.Type type, String target) {
        if (pronoun(target)) return null;
        Command c = new Command(type);
        c.target = looksLikeNumber(target) ? target.replaceAll("[^0-9+]", "") : target.trim();
        return c;
    }

    private static Command onOff(Command.Type type, boolean on) {
        Command c = new Command(type);
        c.on = on;
        return c;
    }

    private static int percent(String n) {
        int v = Integer.parseInt(n);
        return Math.max(0, Math.min(100, v));
    }

    private static Matcher m(String regex, String s) {
        Matcher m = p(regex).matcher(s);
        return m.matches() ? m : null;
    }

    // ------------------------------------------------------------- Russian

    private static final String ON_RU = "(?:включи|включить|зажги|вруби|активируй)", OFF_RU = "(?:выключи|выключить|отключи|погаси|выруби)";

    private static Command ru(String s) {
        Matcher x;
        // translator
        if (is(s, "(?:включи|открой|запусти|включить|открыть) переводчик\\w*|переводчик")) return new Command(Command.Type.TRANSLATOR);
        if ((x = m("переведи(?: это| фразу| текст)?(?: на| в) (арабск\\w*|египетск\\w*|английск\\w*|русск\\w*)(?: язык\\w*)?:? (.+)", s)) != null) {
            return translate(x.group(1), x.group(2));
        }
        if ((x = m("переведи:? (.+?) (?:на|в) (арабск\\w*|египетск\\w*|английск\\w*|русск\\w*)(?: язык\\w*)?", s)) != null) {
            return translate(x.group(2), x.group(1));
        }
        // the time, the date, the battery
        if (is(s, "(?:скажи |подскажи )?(?:который (?:сейчас )?час|сколько (?:сейчас )?времени|сколько время|время|который час сейчас)")) {
            return new Command(Command.Type.TIME);
        }
        if (is(s, "(?:скажи |подскажи )?(?:какое (?:сегодня )?число(?: сегодня)?|какая (?:сегодня )?дата(?: сегодня)?|какой (?:сегодня )?день(?: недели)?(?: сегодня)?|какое сегодня число)")) {
            return new Command(Command.Type.DATE);
        }
        if (is(s, "(?:какой|сколько|уровень)?\\s*(?:сейчас )?(?:у меня )?(?:осталось )?(?:заряд\\w*|батаре\\w*)(?: (?:батаре\\w*|телефона|аккумулятора|осталось))?")
                && (s.contains("заряд") || s.startsWith("какой") || s.startsWith("сколько"))) {
            return new Command(Command.Type.BATTERY);
        }
        // flashlight, radios
        if ((x = m("(" + ON_RU + "|" + OFF_RU + ") фонари\\w*", s)) != null) return onOff(Command.Type.FLASHLIGHT, !x.group(1).matches(OFF_RU));
        if ((x = m("фонари\\w* (вкл\\w*|выкл\\w*)", s)) != null) return onOff(Command.Type.FLASHLIGHT, x.group(1).startsWith("вкл"));
        String wifi = "(?:вай[- ]?фай|wi[- ]?fi|вифи|wifi)", bt = "(?:блютуз|блютус|bluetooth|блю ту[зс])",
                data = "(?:мобильн\\w+ (?:данные|интернет)|передач\\w+ данных|интернет)";
        if ((x = m("(" + ON_RU + "|" + OFF_RU + ") " + wifi, s)) != null) return onOff(Command.Type.WIFI, !x.group(1).matches(OFF_RU));
        if ((x = m("(" + ON_RU + "|" + OFF_RU + ") " + bt, s)) != null) return onOff(Command.Type.BLUETOOTH, !x.group(1).matches(OFF_RU));
        if ((x = m("(" + ON_RU + "|" + OFF_RU + ") " + data, s)) != null) return onOff(Command.Type.MOBILE_DATA, !x.group(1).matches(OFF_RU));
        // ringer
        if (is(s, "(?:включи |поставь (?:на )?|переключи (?:на )?)?(?:режим )?(?:без звука|беззвучн\\w*)(?: режим)?|выключи звук|отключи звук")) {
            return ringer("silent");
        }
        if (is(s, "(?:включи |поставь (?:на )?|переключи (?:на )?)?(?:режим )?(?:вибрац\\w*|на вибрацию)(?: режим)?")) return ringer("vibrate");
        if (is(s, "включи звук|верни звук|(?:поставь )?(?:режим )?со звуком|обычный режим")) return ringer("normal");
        // volume
        if (is(s, "(?:сделай |сделать )?(?:по)?громче|(?:увеличь|прибавь|подними) громкость\\w*")) return level(Command.Type.VOLUME, 1);
        if (is(s, "(?:сделай |сделать )?(?:по)?тише|(?:уменьши|убавь|понизь) громкость\\w*")) return level(Command.Type.VOLUME, -1);
        if ((x = m("(?:сделай |поставь |установи )?громкост\\w*(?: (звонка|рингтона|будильника|музыки|медиа|мультимедиа))?(?: на)? (\\d{1,3}|максимум|минимум|ноль)(?: ?%| процент\\w*)?", s)) != null) {
            Command c = new Command(Command.Type.VOLUME);
            String st = x.group(1);
            c.stream = st == null ? "media" : st.startsWith("звон") || st.startsWith("ринг") ? "ring" : st.startsWith("буд") ? "alarm" : "media";
            c.level = x.group(2).startsWith("макс") ? 100 : x.group(2).startsWith("мин") || x.group(2).equals("ноль") ? 0 : percent(x.group(2));
            return c;
        }
        // brightness
        if (is(s, "(?:включи )?(?:авто ?яркост\\w*|автоматическ\\w+ яркост\\w*|яркость автоматически)")) {
            Command c = new Command(Command.Type.BRIGHTNESS);
            c.auto = true;
            return c;
        }
        if (is(s, "(?:сделай )?(?:по)?ярче|(?:увеличь|прибавь) яркость")) return level(Command.Type.BRIGHTNESS, 1);
        if (is(s, "(?:сделай )?(?:по)?темнее|(?:уменьши|убавь) яркость")) return level(Command.Type.BRIGHTNESS, -1);
        if ((x = m("(?:сделай |поставь |установи )?яркост\\w*(?: экрана)?(?: на)? (\\d{1,3}|максимум|минимум)(?: ?%| процент\\w*)?", s)) != null) {
            Command c = new Command(Command.Type.BRIGHTNESS);
            c.level = x.group(1).startsWith("макс") ? 100 : x.group(1).startsWith("мин") ? 0 : percent(x.group(1));
            return c;
        }
        // camera
        if (is(s, "(?:сделай|сделать|сними|снять)(?: мне)? (?:фото\\w*|снимок|фотку|фотограф\\w*|селфи)(?: .*)?|сфотографируй(?: .*)?|(?:фото|селфи)")) {
            Command c = new Command(Command.Type.PHOTO);
            c.front = s.contains("селфи") || s.contains("фронтальн") || s.contains("меня");
            return c;
        }
        // alarm, timer, reminder
        if ((x = m("(?:поставь|заведи|установи|поставить|завести)? ?будильник (?:на )?(.+)", s)) != null
                || (x = m("разбуди(?: меня)? (?:в|на) (.+)", s)) != null) {
            int[] hm = clock(x.group(1));
            if (hm == null) return null;
            Command c = new Command(Command.Type.ALARM);
            c.hour = hm[0];
            c.minute = hm[1];
            return c;
        }
        if ((x = m("(?:поставь|заведи|установи|включи|запусти)? ?таймер (?:на )?(.+)", s)) != null
                || (x = m("засеки (.+)", s)) != null) {
            long sec = length(x.group(1));
            if (sec <= 0) return null;
            Command c = new Command(Command.Type.TIMER);
            c.seconds = sec;
            return c;
        }
        if ((x = m("напомни(?: мне)? (.+)", s)) != null) return reminder(x.group(1), true);
        // calls and messages
        if ((x = m("(?:позвони|позвонить|набери|звони|вызови|сделай звонок|соедини с)(?: на номер| по номеру)? (.+)", s)) != null) {
            return target(Command.Type.CALL, x.group(1));
        }
        if ((x = m("(?:отправь|пошли|отправить|напиши|написать)(?: (смс\\w*|sms|сообщение|эсэмэс\\w*|смску))? (.+)", s)) != null
                || (x = m("(смс|sms|сообщение)() (.+)", s)) != null) {
            boolean said = x.group(1) != null && !x.group(1).isEmpty();
            return sms(x.group(x.groupCount()), said, "(?:\\s*:\\s*|\\s+(?:с текстом|текст|что|со словами|чтобы)\\s+)");
        }
        // apps (last: "включи …" above was something else)
        if ((x = m("(?:открой|запусти|открыть|запустить|включи)(?: приложение)? (.+)", s)) != null) {
            Command c = new Command(Command.Type.OPEN_APP);
            c.target = x.group(1).trim();
            return c;
        }
        return null;
    }

    private static Command translate(String langWord, String text) {
        Command c = new Command(Command.Type.TRANSLATE);
        String w = langWord.toLowerCase(Locale.ROOT);
        c.toLang = w.startsWith("араб") || w.startsWith("егип") || w.startsWith("arab") || w.startsWith("egypt") ? "ar"
                : w.startsWith("англ") || w.startsWith("engl") ? "en" : "ru";
        c.text = text.trim();
        return c.text.isEmpty() ? null : c;
    }

    private static Command ringer(String mode) {
        Command c = new Command(Command.Type.RINGER);
        c.ringer = mode;
        return c;
    }

    private static Command level(Command.Type t, int delta) {
        Command c = new Command(t);
        c.delta = delta;
        return c;
    }

    /** "маме что я опоздаю", "маме: привет" → target, text. Without a marker: the first word, the rest. */
    private static Command sms(String rest, boolean said, String marker) {
        String[] parts = rest.split(marker, 2);
        String to, text;
        if (parts.length == 2) {
            to = parts[0];
            text = parts[1];
        } else {
            if (!said) return null; // "напиши маме" with no message, or "напиши письмо…": the agent's
            int sp = rest.indexOf(' ');
            if (sp < 0) return null;
            to = rest.substring(0, sp);
            text = rest.substring(sp + 1);
        }
        to = to.replaceFirst("^(?:для|to)\\s+", "").trim();
        if (text.trim().isEmpty() || to.isEmpty() || pronoun(to)) return null;
        Command c = target(Command.Type.SMS, to);
        if (c != null) c.text = text.trim();
        return c;
    }

    /** "через 10 минут позвонить маме", "позвонить маме в 18:30", "in 5 minutes to call mom". */
    private static Command reminder(String rest, boolean ru) {
        String in = ru ? "через" : "in";
        Command c = new Command(Command.Type.REMINDER);
        Matcher x = p("(?:^|\\s)" + in + "\\s+(" + LENGTH + ")(?=\\s|$)").matcher(rest);
        if (x.find()) {
            c.seconds = length(x.group(1));
            rest = (rest.substring(0, x.start()) + " " + rest.substring(x.end())).trim();
        } else {
            Matcher y = p("(?:^|\\s)(?:в|at)\\s+(\\d{1,2}(?:[:.]\\d{2}|\\s\\d{2}(?=\\s|$))?(?:\\s+(?:утра|вечера|дня|ночи|am|pm))?)(?=\\s|$)")
                    .matcher(rest);
            if (!y.find()) return null;
            int[] hm = clock(y.group(1));
            if (hm == null) return null;
            c.hour = hm[0];
            c.minute = hm[1];
            rest = (rest.substring(0, y.start()) + " " + rest.substring(y.end())).trim();
        }
        rest = rest.replaceFirst("^(?:о том,? что|о том|что|чтобы|о|об|про|to|that|about)\\s+", "").trim();
        if (rest.isEmpty() || c.seconds < 0) return null;
        c.text = rest;
        return c;
    }

    // ------------------------------------------------------------- English

    private static Command en(String s) {
        Matcher x;
        if (is(s, "(?:open|start|launch|turn on) (?:the )?translator")) return new Command(Command.Type.TRANSLATOR);
        if ((x = m("translate(?: this)? (?:to|into) (arabic|egyptian|english|russian):? (.+)", s)) != null) return translate(x.group(1), x.group(2));
        if ((x = m("translate:? (.+?) (?:to|into) (arabic|egyptian|english|russian)", s)) != null) return translate(x.group(2), x.group(1));
        if (is(s, "(?:what time is it|what'?s the time|what is the time|time)(?: now)?")) return new Command(Command.Type.TIME);
        if (is(s, "(?:what'?s|what is) (?:the date|today'?s date)(?: today)?|what day is (?:it|today)|what'?s today")) return new Command(Command.Type.DATE);
        if (is(s, "(?:what'?s|what is|how much) (?:my |the )?battery(?: level| left| charge)?|battery(?: level)?")) return new Command(Command.Type.BATTERY);
        if ((x = m("(?:turn|switch) (on|off) (?:the )?(?:flash ?light|torch|light)", s)) != null
                || (x = m("(?:flash ?light|torch) (on|off)", s)) != null) {
            return onOff(Command.Type.FLASHLIGHT, x.group(1).equals("on"));
        }
        String radios = "(wi ?-?fi|bluetooth|mobile data|cellular data|data)";
        if ((x = m("(?:turn|switch) (on|off) (?:the )?" + radios, s)) != null) return radio(x.group(2), x.group(1).equals("on"));
        if ((x = m("(enable|disable) (?:the )?" + radios, s)) != null) return radio(x.group(2), x.group(1).equals("enable"));
        if ((x = m(radios + " (on|off)", s)) != null) return radio(x.group(1), x.group(2).equals("on"));
        if (is(s, "(?:turn on |switch to |set )?(?:silent|mute)(?: mode)?|mute(?: the phone)?|(?:turn|switch) off (?:the )?sound")) return ringer("silent");
        if (is(s, "(?:turn on |switch to |set )?vibrat(?:e|ion)(?: mode)?")) return ringer("vibrate");
        if (is(s, "unmute(?: the phone)?|(?:turn|switch) on (?:the )?sound|sound on|normal mode")) return ringer("normal");
        if (is(s, "louder|volume up|turn (?:it |the volume )?up|increase (?:the )?volume")) return level(Command.Type.VOLUME, 1);
        if (is(s, "quieter|softer|volume down|turn (?:it |the volume )?down|decrease (?:the )?volume")) return level(Command.Type.VOLUME, -1);
        if ((x = m("(?:set )?(?:the )?(ring(?:er|tone)?|alarm|media|music)? ?volume (?:to )?(\\d{1,3}|max(?:imum)?|min(?:imum)?)(?: ?%| percent)?", s)) != null) {
            Command c = new Command(Command.Type.VOLUME);
            String st = x.group(1);
            c.stream = st == null ? "media" : st.startsWith("ring") ? "ring" : st.equals("alarm") ? "alarm" : "media";
            c.level = x.group(2).startsWith("max") ? 100 : x.group(2).startsWith("min") ? 0 : percent(x.group(2));
            return c;
        }
        if (is(s, "(?:turn on )?auto(?:matic)? ?brightness")) {
            Command c = new Command(Command.Type.BRIGHTNESS);
            c.auto = true;
            return c;
        }
        if (is(s, "brighter|brightness up|increase (?:the )?brightness")) return level(Command.Type.BRIGHTNESS, 1);
        if (is(s, "dimmer|darker|brightness down|decrease (?:the )?brightness")) return level(Command.Type.BRIGHTNESS, -1);
        if ((x = m("(?:set )?(?:the )?(?:screen )?brightness (?:to )?(\\d{1,3}|max(?:imum)?|min(?:imum)?)(?: ?%| percent)?", s)) != null) {
            Command c = new Command(Command.Type.BRIGHTNESS);
            c.level = x.group(1).startsWith("max") ? 100 : x.group(1).startsWith("min") ? 0 : percent(x.group(1));
            return c;
        }
        if (is(s, "take (?:a |me a )?(?:photo|picture|pic|selfie|snapshot)(?: .*)?")) {
            Command c = new Command(Command.Type.PHOTO);
            c.front = s.contains("selfie") || s.contains("front");
            return c;
        }
        if ((x = m("(?:set )?(?:an |the |my )?alarm (?:for |at )?(.+)", s)) != null || (x = m("wake me(?: up)? at (.+)", s)) != null) {
            int[] hm = clock(x.group(1));
            if (hm == null) return null;
            Command c = new Command(Command.Type.ALARM);
            c.hour = hm[0];
            c.minute = hm[1];
            return c;
        }
        if ((x = m("(?:set |start )?(?:a |the )?timer (?:for )?(.+)", s)) != null
                || (x = m("(?:set |start )?(?:a |an )?(.+?) timer", s)) != null) {
            long sec = length(x.group(1).replace(" long", ""));
            if (sec <= 0) return null;
            Command c = new Command(Command.Type.TIMER);
            c.seconds = sec;
            return c;
        }
        if ((x = m("remind me (.+)", s)) != null) return reminder(x.group(1), false);
        if ((x = m("(?:call|dial|phone|ring)(?: the number)? (.+)", s)) != null) return target(Command.Type.CALL, x.group(1));
        if ((x = m("(?:send )?(?:a )?(text|sms|message)(?: message)?(?: to)? (.+)", s)) != null
                || (x = m("(text|message) (.+)", s)) != null) {
            return sms(x.group(2), true, "(?:\\s*:\\s*|\\s+(?:saying|that says|that|with)\\s+)");
        }
        if ((x = m("(?:open|launch|start)(?: the)?(?: app)? (.+)", s)) != null) {
            Command c = new Command(Command.Type.OPEN_APP);
            c.target = x.group(1).trim();
            return c;
        }
        return null;
    }

    private static Command radio(String which, boolean on) {
        Command.Type t = which.startsWith("wi") ? Command.Type.WIFI : which.equals("bluetooth") ? Command.Type.BLUETOOTH
                : Command.Type.MOBILE_DATA;
        return onOff(t, on);
    }
}
