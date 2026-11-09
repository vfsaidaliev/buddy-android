package app.buddy.assistant;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The voice assistant's offline commands (CommandParser) on everyday phrases, Russian and English. */
public class CommandParserTest {
    private static Command ru(String s) {
        return CommandParser.parse(s, "ru-RU");
    }

    private static Command en(String s) {
        return CommandParser.parse(s, "en-US");
    }

    private static Command is(Command c, Command.Type t) {
        assertNotNull("not understood", c);
        assertEquals(c.toString(), t, c.type);
        return c;
    }

    // ------------------------------------------------------------- Russian

    @Test
    public void callRu() {
        assertEquals("маме", is(ru("Позвони маме"), Command.Type.CALL).target);
        assertEquals("саше петрову", is(ru("набери Саше Петрову"), Command.Type.CALL).target);
        assertEquals("+79991234567", is(ru("позвони на номер +7 999 123-45-67"), Command.Type.CALL).target);
        assertNull(ru("позвони ему"));
    }

    @Test
    public void smsRu() {
        Command c = is(ru("Напиши маме, что я опоздаю"), Command.Type.SMS);
        assertEquals("маме", c.target);
        assertEquals("я опоздаю", c.text);
        c = is(ru("отправь смс Саше привет как дела"), Command.Type.SMS);
        assertEquals("саше", c.target);
        assertEquals("привет как дела", c.text);
        c = is(ru("смс папе: буду в восемь"), Command.Type.SMS);
        assertEquals("папе", c.target);
        assertEquals("буду в 8", c.text);
        assertNull(ru("а теперь отправь ему привет"));
        assertNull(ru("отправь ему привет"));
        assertNull(ru("напиши маме"));
    }

    @Test
    public void alarmRu() {
        Command c = is(ru("Поставь будильник на 7:30"), Command.Type.ALARM);
        assertEquals(7, c.hour);
        assertEquals(30, c.minute);
        c = is(ru("будильник на семь утра"), Command.Type.ALARM);
        assertEquals(7, c.hour);
        assertEquals(0, c.minute);
        c = is(ru("будильник на восемь вечера"), Command.Type.ALARM);
        assertEquals(20, c.hour);
        c = is(ru("разбуди меня в 6 30"), Command.Type.ALARM);
        assertEquals(6, c.hour);
        assertEquals(30, c.minute);
        c = is(ru("будильник на семь тридцать"), Command.Type.ALARM);
        assertEquals(7, c.hour);
        assertEquals(30, c.minute);
    }

    @Test
    public void timerRu() {
        assertEquals(300, is(ru("Поставь таймер на 5 минут"), Command.Type.TIMER).seconds);
        assertEquals(300, is(ru("таймер на пять минут"), Command.Type.TIMER).seconds);
        assertEquals(1800, is(ru("таймер на полчаса"), Command.Type.TIMER).seconds);
        assertEquals(5400, is(ru("засеки 1 час 30 минут"), Command.Type.TIMER).seconds);
        assertEquals(1500, is(ru("таймер на двадцать пять минут"), Command.Type.TIMER).seconds);
        assertEquals(60, is(ru("таймер на минуту"), Command.Type.TIMER).seconds);
    }

    @Test
    public void reminderRu() {
        Command c = is(ru("напомни через 10 минут позвонить маме"), Command.Type.REMINDER);
        assertEquals(600, c.seconds);
        assertEquals("позвонить маме", c.text);
        c = is(ru("Напомни мне в 18:00 купить хлеб"), Command.Type.REMINDER);
        assertEquals(18, c.hour);
        assertEquals(0, c.minute);
        assertEquals("купить хлеб", c.text);
        c = is(ru("напомни выключить духовку через час"), Command.Type.REMINDER);
        assertEquals(3600, c.seconds);
        assertEquals("выключить духовку", c.text);
        assertNull(ru("напомни купить молоко"));
    }

    @Test
    public void switchesRu() {
        assertTrue(is(ru("Включи фонарик"), Command.Type.FLASHLIGHT).on);
        assertFalse(is(ru("выключи фонарик"), Command.Type.FLASHLIGHT).on);
        assertTrue(is(ru("включи вай-фай"), Command.Type.WIFI).on);
        assertFalse(is(ru("выключи wi-fi"), Command.Type.WIFI).on);
        assertFalse(is(ru("отключи блютуз"), Command.Type.BLUETOOTH).on);
        assertTrue(is(ru("включи bluetooth"), Command.Type.BLUETOOTH).on);
        assertFalse(is(ru("выключи мобильные данные"), Command.Type.MOBILE_DATA).on);
    }

    @Test
    public void soundRu() {
        Command c = is(ru("Громкость 50"), Command.Type.VOLUME);
        assertEquals("media", c.stream);
        assertEquals(50, c.level);
        c = is(ru("громкость звонка на 30 процентов"), Command.Type.VOLUME);
        assertEquals("ring", c.stream);
        assertEquals(30, c.level);
        assertEquals(1, is(ru("сделай громче"), Command.Type.VOLUME).delta);
        assertEquals(-1, is(ru("тише"), Command.Type.VOLUME).delta);
        assertEquals("silent", is(ru("без звука"), Command.Type.RINGER).ringer);
        assertEquals("vibrate", is(ru("режим вибрации"), Command.Type.RINGER).ringer);
        assertEquals("normal", is(ru("включи звук"), Command.Type.RINGER).ringer);
    }

    @Test
    public void brightnessRu() {
        assertEquals(40, is(ru("яркость 40"), Command.Type.BRIGHTNESS).level);
        assertEquals(100, is(ru("яркость на максимум"), Command.Type.BRIGHTNESS).level);
        assertEquals(1, is(ru("ярче"), Command.Type.BRIGHTNESS).delta);
        assertEquals(-1, is(ru("сделай темнее"), Command.Type.BRIGHTNESS).delta);
        assertTrue(is(ru("автояркость"), Command.Type.BRIGHTNESS).auto);
    }

    @Test
    public void appsAndCameraRu() {
        assertEquals("телеграм", is(ru("Открой Телеграм"), Command.Type.OPEN_APP).target);
        assertEquals("яндекс карты", is(ru("запусти приложение яндекс карты"), Command.Type.OPEN_APP).target);
        assertFalse(is(ru("сделай фото"), Command.Type.PHOTO).front);
        assertTrue(is(ru("сделай селфи"), Command.Type.PHOTO).front);
        is(ru("сфотографируй"), Command.Type.PHOTO);
    }

    @Test
    public void questionsRu() {
        is(ru("Который час?"), Command.Type.TIME);
        is(ru("сколько времени"), Command.Type.TIME);
        is(ru("какое сегодня число"), Command.Type.DATE);
        is(ru("какой заряд"), Command.Type.BATTERY);
        is(ru("сколько заряда"), Command.Type.BATTERY);
        is(ru("заряд батареи"), Command.Type.BATTERY);
    }

    @Test
    public void translatorRu() {
        Command c = is(ru("переведи на арабский где находится вокзал"), Command.Type.TRANSLATE);
        assertEquals("ar", c.toLang);
        assertEquals("где находится вокзал", c.text);
        c = is(ru("переведи доброе утро на английский"), Command.Type.TRANSLATE);
        assertEquals("en", c.toLang);
        assertEquals("доброе утро", c.text);
        is(ru("включи переводчик"), Command.Type.TRANSLATOR);
    }

    @Test
    public void notCommandsRu() {
        assertNull(ru("расскажи анекдот"));
        assertNull(ru("какая погода завтра в москве"));
        assertNull(ru("найди рецепт борща"));
        assertNull(ru(""));
    }

    // ------------------------------------------------------------- English

    @Test
    public void english() {
        assertEquals("mom", is(en("Call mom"), Command.Type.CALL).target);
        Command c = is(en("text John saying I'm late"), Command.Type.SMS);
        assertEquals("john", c.target);
        assertEquals("i'm late", c.text);
        c = is(en("Set an alarm for 7 am"), Command.Type.ALARM);
        assertEquals(7, c.hour);
        c = is(en("set alarm for 7:30 pm"), Command.Type.ALARM);
        assertEquals(19, c.hour);
        assertEquals(30, c.minute);
        c = is(en("wake me up at 6:30"), Command.Type.ALARM);
        assertEquals(6, c.hour);
        assertEquals(600, is(en("set a timer for 10 minutes"), Command.Type.TIMER).seconds);
        assertEquals(300, is(en("start a five minute timer"), Command.Type.TIMER).seconds);
        c = is(en("remind me in 5 minutes to call mom"), Command.Type.REMINDER);
        assertEquals(300, c.seconds);
        assertEquals("call mom", c.text);
        assertTrue(is(en("turn on the flashlight"), Command.Type.FLASHLIGHT).on);
        assertFalse(is(en("turn off wifi"), Command.Type.WIFI).on);
        assertTrue(is(en("bluetooth on"), Command.Type.BLUETOOTH).on);
        assertEquals(30, is(en("volume 30"), Command.Type.VOLUME).level);
        assertEquals("silent", is(en("silent mode"), Command.Type.RINGER).ringer);
        assertEquals(1, is(en("brighter"), Command.Type.BRIGHTNESS).delta);
        assertEquals("spotify", is(en("open Spotify"), Command.Type.OPEN_APP).target);
        assertTrue(is(en("take a selfie"), Command.Type.PHOTO).front);
        is(en("What time is it?"), Command.Type.TIME);
        is(en("what's the date"), Command.Type.DATE);
        is(en("battery level"), Command.Type.BATTERY);
        c = is(en("translate to arabic where is the station"), Command.Type.TRANSLATE);
        assertEquals("ar", c.toLang);
        assertEquals("where is the station", c.text);
        assertNull(en("tell me a joke"));
        assertNull(en("text him hello"));
    }

    @Test
    public void numbersAndTimes() {
        assertEquals("25 минут", CommandParser.normalize("двадцать пять минут"));
        assertEquals("21", CommandParser.normalize("twenty-one"));
        int[] hm = CommandParser.clock("в 7 часов 30 минут вечера");
        assertNotNull(hm);
        assertEquals(19, hm[0]);
        assertEquals(30, hm[1]);
        assertNull(CommandParser.clock("завтра"));
        assertEquals(5400, CommandParser.length("1 час 30 минут"));
        assertEquals(3600, CommandParser.length("an hour"));
        assertEquals(-1, CommandParser.length("купить хлеб"));
    }
}
