package dev.codecomp.repo;

import dev.codecomp.run.RateLimiter;
import dev.codecomp.sandbox.Sandbox;
import dev.codecomp.sandbox.SandboxConfig;
import dev.codecomp.web.Api.EditRequest;
import dev.codecomp.web.Api.EditResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** Inline AI edits: the user selects code, describes a change, and gets the rewritten selection back. */
@Service
public class CodeEditService {

    private final CodeEditAssistant assistant;
    private final RateLimiter limiter;
    private final SandboxConfig sandbox;

    CodeEditService(CodeEditAssistant assistant, RateLimiter limiter, SandboxConfig sandbox) {
        this.assistant = assistant;
        this.limiter = limiter;
        this.sandbox = sandbox;
    }

    public EditResult edit(UUID user, EditRequest req) {
        if (!sandbox.languages().containsKey(req.language())) throw new Sandbox.UnsupportedLanguageException(req.language());
        if (!req.code().contains(req.selection()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The selection is not part of the code");
        limiter.acquire(user);
        var edit = assistant.rewrite(req.language(), req.code(), req.selection(), req.instruction());
        var note = edit.note() == null || edit.note().isBlank() ? null : edit.note().strip();
        return new EditResult(edit.replacement() == null ? req.selection() : edit.replacement(), note);
    }
}
