package app.buddy.assistant;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Fuzzy matching of spoken names against contacts (Fuzzy). */
public class FuzzyTest {
    @Test
    public void caseEndings() {
        assertTrue(Fuzzy.score("маме", "Мама") >= Fuzzy.MATCH);
        assertTrue(Fuzzy.score("саше", "Саша Петров") >= Fuzzy.MATCH);
        assertTrue(Fuzzy.score("ивану петровичу", "Иван Петрович") >= Fuzzy.MATCH);
        assertTrue(Fuzzy.score("сергею", "Сергей") >= Fuzzy.MATCH);
    }

    @Test
    public void latinAndCyrillic() {
        assertTrue(Fuzzy.score("alex", "Алекс") >= Fuzzy.MATCH);
        assertTrue(Fuzzy.score("максу", "Max") >= Fuzzy.MATCH);
    }

    @Test
    public void differentNames() {
        assertTrue(Fuzzy.score("мише", "Маша") < Fuzzy.MATCH);
        assertTrue(Fuzzy.score("папе", "Мама") < Fuzzy.MATCH);
    }

    @Test
    public void bestAndTies() {
        List<String> contacts = Arrays.asList("Саша Петров", "Саша Иванова", "Маша", "Мама");
        assertEquals(2, Fuzzy.best("саше", contacts).size());
        assertEquals(Arrays.asList(3), Fuzzy.best("маме", contacts));
        assertEquals(Arrays.asList(1), Fuzzy.best("саше ивановой", contacts));
        assertTrue(Fuzzy.best("дяде феде", contacts).isEmpty());
    }
}
