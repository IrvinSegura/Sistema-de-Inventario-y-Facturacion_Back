package com.irvin.inventory.common.email;

import org.springframework.boot.context.properties.ConfigurationProperties;

// from: the sender, e.g. "Inventory <no-reply@example.com>". appUrl: base address of the web client that
// the emails' buttons open; without one, emails go out with no button.
@ConfigurationProperties("app.email")
public record EmailProperties(String from, String appUrl) {
}
