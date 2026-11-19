package dev.codecomp.repo;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** Rewrites a selected piece of code according to the user's instruction. */
public interface CodeEditAssistant {

    CodeEdit rewrite(String language, String file, String selection, String instruction);

    record CodeEdit(
            @JsonPropertyDescription("the text that replaces the selection, exactly as it should appear in the file; the unchanged selection if no edit was made")
            String replacement,
            @JsonPropertyDescription("empty when the edit was made; otherwise one short sentence telling the user why nothing was changed")
            String note) {}
}
