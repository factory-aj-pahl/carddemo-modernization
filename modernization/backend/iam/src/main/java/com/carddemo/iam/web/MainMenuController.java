package com.carddemo.iam.web;

import com.carddemo.common.msg.CommonMessages;
import com.carddemo.common.screen.Key;
import com.carddemo.common.screen.MessageColor;
import com.carddemo.common.screen.ScreenResponse;
import com.carddemo.common.security.CurrentUser;
import com.carddemo.iam.internal.MenuCatalog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** COMEN01C main menu (transaction CM00). */
@RestController
@RequestMapping("/api/screens/COMEN01C")
@Tag(name = "COMEN01C", description = "Main menu")
public class MainMenuController {

    /** @param options option lines {@code nn. <name>}; @param option the echoed option field */
    public record Fields(List<String> options, String option) {
    }

    public record Request(Key key, String option) {
    }

    private final MenuCatalog catalog;

    public MainMenuController(MenuCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    @Operation(summary = "First entry: menu with empty option and no message (BR-COMEN01C-02)")
    public ScreenResponse<Fields> show() {
        return ScreenResponse.show(fields("")).withFocus("option");
    }

    @PostMapping
    @Operation(summary = "Menu turn: ENTER selects an option, PF3 signs off, other keys are invalid")
    public ScreenResponse<Fields> turn(@RequestBody Request req, HttpServletRequest http) {
        Key key = req.key() == null ? Key.ENTER : req.key();
        if (key == Key.PF3) {
            HttpSession s = http.getSession(false);
            if (s != null) {
                s.invalidate();
            }
            return ScreenResponse.navigate("COSGN00C");
        }
        String raw = req.option() == null ? "" : req.option();
        if (key != Key.ENTER) {
            return ScreenResponse.show(fields(raw)).error(CommonMessages.INVALID_KEY).withFocus("option");
        }
        String normalized = normalize(raw);
        int n = normalized.chars().allMatch(Character::isDigit) ? Integer.parseInt(normalized) : -1;
        List<MenuCatalog.Option> options = catalog.main();
        if (n < 1 || n > options.size()) {
            return ScreenResponse.show(fields(normalized)).error("Please enter a valid option number...").withFocus("option");
        }
        MenuCatalog.Option opt = options.get(n - 1);
        if (opt.userType().equals("A") && !CurrentUser.require().isAdmin()) {
            return ScreenResponse.show(fields(normalized)).error(CommonMessages.ADMIN_ONLY).withFocus("option");
        }
        if (!catalog.installed(opt.program())) {
            return ScreenResponse.show(fields(normalized))
                .withMessage("This option " + opt.name() + " is not installed...", MessageColor.RED).withFocus("option");
        }
        return ScreenResponse.navigate(opt.program());
    }

    /**
     * BR-COMEN01C-04: ignore trailing spaces, right-justify in two positions, leading spaces become
     * zeros. Only the first two characters exist on the screen.
     */
    static String normalize(String raw) {
        String s = raw.length() > 2 ? raw.substring(0, 2) : raw;
        int end = s.length();
        while (end > 1 && s.charAt(end - 1) == ' ') {
            end--;
        }
        String part = s.substring(0, end);
        String justified = part.length() >= 2 ? part : " ".repeat(2 - part.length()) + part;
        return justified.replace(' ', '0');
    }

    private Fields fields(String option) {
        return new Fields(catalog.main().stream().map(MenuCatalog.Option::line).toList(), option);
    }
}
