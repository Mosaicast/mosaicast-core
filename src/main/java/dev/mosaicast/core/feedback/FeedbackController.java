// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 The Mosaicast Authors

package dev.mosaicast.core.feedback;

import dev.mosaicast.core.auth.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The visitor side of feedback (ARCHITECTURE §20): a proof-of-work challenge for anonymous senders, and the
 * report itself. Both are open without an account — the commonest thing to report is a sign-in that failed —
 * and both answer 404 when the operator has the form off or points elsewhere.
 */
@RestController
public class FeedbackController {

    private final FeedbackService feedback;

    public FeedbackController(FeedbackService feedback) {
        this.feedback = feedback;
    }

    @GetMapping("/api/feedback/challenge")
    public ProofOfWork.Challenge challenge() {
        return feedback.challenge();
    }

    /**
     * Takes a report. 202 whether it was stored, recognised as a repeat, or caught by the honeypot: none of those
     * is something a sender should be able to tell apart.
     */
    @PostMapping("/api/feedback")
    public ResponseEntity<Void> submit(@RequestBody FeedbackService.Submission submission, HttpServletRequest request,
                                       Authentication authentication) {
        // The address is only a rate-limit key, held in memory for the window and never stored (§19's rule).
        feedback.submit(submission, CurrentUser.id(authentication), request.getRemoteAddr());
        return ResponseEntity.accepted().build();
    }
}
