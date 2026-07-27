package com.swarmhq.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Spring's static-resource handling serves /app/index.html directly but
 * doesn't resolve /app or /app/ to it on their own (that auto-resolution
 * only applies to the root welcome page) - this forwards both.
 */
@Controller
public class SpaController {

    @GetMapping({"/app", "/app/"})
    public String index() {
        return "forward:/app/index.html";
    }
}
