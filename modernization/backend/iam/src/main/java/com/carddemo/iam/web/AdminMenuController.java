package com.carddemo.iam.web;

import com.carddemo.common.msg.CommonMessages;
import com.carddemo.common.screen.Key;
import com.carddemo.common.screen.MessageColor;
import com.carddemo.common.screen.ScreenResponse;
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

/** COADM01C admin menu (transaction CA00). ADMIN only (C-ROLE, Q-COADM01C-01), enforced in SecurityConfig. */
@RestController
@RequestMapping("/api/screens/COADM01C")
@Tag(name = "COADM01C", description = "Admin menu")
public class AdminMenuController {

    /** Q-COADM01C-03: green, without the option name. */
    static final String NOT_INSTALLED = "This option is not installed ...";

    public record Fields(List<String> options, String option) {
    }

    public record Request(Key key, String option) {
    }

    private final MenuCatalog catalog;

    public AdminMenuController(MenuCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    @Operation(summary = "First entry: admin menu with empty option and no message (BR-COADM01C-02)")
    public ScreenResponse<Fields> show() {
        return ScreenResponse.show(fields("")).withFocus("option");
    }

    @PostMapping
    @Operation(summary = "Admin menu turn: ENTER selects an option, PF3 signs off, other keys are invalid")
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
        String normalized = MainMenuController.normalize(raw);
        int n = normalized.chars().allMatch(Character::isDigit) ? Integer.parseInt(normalized) : -1;
        List<MenuCatalog.Option> options = catalog.admin();
        if (n < 1 || n > options.size()) {
            return ScreenResponse.show(fields(normalized)).error("Please enter a valid option number...").withFocus("option");
        }
        MenuCatalog.Option opt = options.get(n - 1);
        if (!catalog.installed(opt.program())) {
            return ScreenResponse.show(fields(normalized)).withMessage(NOT_INSTALLED, MessageColor.GREEN).withFocus("option");
        }
        return ScreenResponse.navigate(opt.program());
    }

    private Fields fields(String option) {
        return new Fields(catalog.admin().stream().map(MenuCatalog.Option::line).toList(), option);
    }
}
