package com.investmentresearch.core.citation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.investmentresearch.core.domain.Source;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Expectations below were derived by running Python's {@code validate_citations} from {@code
 * assistant/citations.py} on the same inputs; issue strings are the API pass-through text.
 */
class CitationValidatorTest {

    private static final Source FIN =
            new Source("finance:605499:2026-06-30", "财报", "d1", "2026-06-30", "t1");
    private static final Source SEG =
            new Source("segments:605499:2026-06-30:product", "主营构成", "d2", "2026-06-30", "t1");
    private static final Map<String, Source> REGISTRY =
            Map.of(FIN.id(), FIN, SEG.id(), SEG);

    private final CitationValidator validator = new CitationValidator();

    @Test
    void known_id_is_kept_and_registered_in_first_seen_order() {
        String text =
                "营收 124.4 亿[[finance:605499:2026-06-30]]，能量饮料"
                        + "[[segments:605499:2026-06-30:product]]。";

        CitationCheck check = validator.validate(text, REGISTRY);

        assertEquals(text, check.text());
        assertEquals(List.of(FIN, SEG), check.citations());
        assertEquals(List.of(), check.issues());
    }

    @Test
    void unknown_id_becomes_question_mark_and_records_issue() {
        CitationCheck check = validator.validate("净利增长[[made-up-id]]。", REGISTRY);

        assertEquals("净利增长[?]。", check.text());
        assertEquals(List.of(), check.citations());
        assertEquals(List.of("unknown_citation"), check.issues());
    }

    @Test
    void repeated_id_is_registered_once() {
        String text =
                "a[[finance:605499:2026-06-30]] b[[finance:605499:2026-06-30]]";

        CitationCheck check = validator.validate(text, REGISTRY);

        assertEquals(text, check.text());
        assertEquals(List.of(FIN), check.citations());
        assertEquals(List.of(), check.issues());
    }

    @Test
    void digits_without_citations_records_uncited_numbers() {
        String text = "营收 124.4 亿，净利 28.7 亿。";

        CitationCheck check = validator.validate(text, REGISTRY);

        assertEquals(text, check.text());
        assertEquals(List.of(), check.citations());
        assertEquals(List.of("回答里有数字，但没有任何有效引用"), check.issues());
    }

    @Test
    void no_digits_no_citations_has_no_issue() {
        CitationCheck check = validator.validate("公司卖能量饮料。", REGISTRY);

        assertEquals(List.of(), check.citations());
        assertEquals(List.of(), check.issues());
    }

    @Test
    void nested_brackets_are_not_matched() {
        // Python matches the inner [[...]] pair and leaves the outer brackets untouched.
        CitationCheck known =
                validator.validate("[[[finance:605499:2026-06-30]]]", REGISTRY);
        assertEquals("[[[finance:605499:2026-06-30]]]", known.text());
        assertEquals(List.of(FIN), known.citations());
        assertEquals(List.of(), known.issues());

        CitationCheck unknown = validator.validate("[[[made-up-id]]]", REGISTRY);
        assertEquals("[[?]]", unknown.text());
        assertEquals(List.of("unknown_citation"), unknown.issues());

        CitationCheck plain = validator.validate("这是 [正常的] 方括号。", REGISTRY);
        assertEquals("这是 [正常的] 方括号。", plain.text());
        assertEquals(List.of(), plain.issues());
    }

    @Test
    void id_is_trimmed_before_lookup() {
        // Matched text keeps its original whitespace; the lookup uses the trimmed id.
        String text = "x[[   finance:605499:2026-06-30\t]]y";
        CitationCheck check = validator.validate(text, REGISTRY);
        assertEquals(text, check.text());
        assertEquals(List.of(FIN), check.citations());
        assertEquals(List.of(), check.issues());

        // Python's str.strip() also drops NBSP, which String.strip() does not.
        CitationCheck nbsp =
                validator.validate("x[[\u00a0finance:605499:2026-06-30\u00a0]]y", REGISTRY);
        assertEquals(List.of(FIN), nbsp.citations());
        assertEquals(List.of(), nbsp.issues());
    }

    @Test
    void lookup_is_case_sensitive() {
        CitationCheck unknown =
                validator.validate("[[FINANCE:605499:2026-06-30]]", REGISTRY);
        assertEquals("[?]", unknown.text());
        assertEquals(List.of("unknown_citation"), unknown.issues());

        CitationCheck mixed =
                validator.validate(
                        "[[finance:605499:2026-06-30]] [[FINANCE:605499:2026-06-30]]", REGISTRY);
        assertEquals("[[finance:605499:2026-06-30]] [?]", mixed.text());
        assertEquals(List.of(FIN), mixed.citations());
        assertEquals(List.of("unknown_citation"), mixed.issues());
    }

    @Test
    void unknown_issue_precedes_uncited_numbers() {
        CitationCheck check = validator.validate("[[bad1]] 营收 5 亿", REGISTRY);

        assertEquals("[?] 营收 5 亿", check.text());
        assertEquals(List.of("unknown_citation", "回答里有数字，但没有任何有效引用"), check.issues());
    }

    @Test
    void every_unknown_occurrence_records_an_issue() {
        CitationCheck check = validator.validate("[[a]][[b]]", REGISTRY);

        assertEquals("[?][?]", check.text());
        assertEquals(List.of("unknown_citation", "unknown_citation"), check.issues());
    }

    @Test
    void a_known_citation_suppresses_uncited_numbers() {
        CitationCheck check =
                validator.validate("[[finance:605499:2026-06-30]] 营收 5 亿", REGISTRY);

        assertEquals(List.of(FIN), check.citations());
        assertEquals(List.of(), check.issues());
    }

    @Test
    void unicode_decimal_digits_count_but_superscripts_do_not() {
        // Python's \d matches Unicode Nd: full-width and Arabic-Indic digits count.
        assertEquals(
                List.of("回答里有数字，但没有任何有效引用"),
                validator.validate("营收 １２３ 亿", REGISTRY).issues());
        assertEquals(
                List.of("回答里有数字，但没有任何有效引用"),
                validator.validate("营收 ١٢٣ 亿", REGISTRY).issues());
        // ² is category No, not Nd, so Python finds no digit.
        assertEquals(List.of(), validator.validate("营收 ² 亿", REGISTRY).issues());
    }

    @Test
    void whitespace_only_id_is_unknown() {
        CitationCheck check = validator.validate("x[[   ]]y", REGISTRY);

        assertEquals("x[?]y", check.text());
        assertEquals(List.of("unknown_citation"), check.issues());
    }

    @Test
    void known_id_with_regex_replacement_characters_is_kept_verbatim() {
        Source dollar = new Source("a$b", "L", "d", "x", "t");
        Map<String, Source> registry = Map.of(dollar.id(), dollar);

        CitationCheck check = validator.validate("x[[a$b]]y", registry);

        assertEquals("x[[a$b]]y", check.text());
        assertEquals(List.of(dollar), check.citations());
    }

    @Test
    void null_text_is_treated_as_empty() {
        CitationCheck check = validator.validate(null, REGISTRY);

        assertEquals("", check.text());
        assertEquals(List.of(), check.citations());
        assertEquals(List.of(), check.issues());
    }
}
