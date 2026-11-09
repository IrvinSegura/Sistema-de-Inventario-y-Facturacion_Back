package com.irvin.inventory.user;

import com.irvin.inventory.common.email.EmailMessage;

// Delivers account notices; none is configured without an SMTP host, and then they're only logged.
public interface AccountNoticeSender {

    void send(EmailMessage notice);
}
