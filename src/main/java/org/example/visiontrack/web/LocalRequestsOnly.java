package org.example.visiontrack.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** The APIs answer requests from this computer only, even if {@code server.address} is changed. */
@ControllerAdvice(annotations = RestController.class)
public class LocalRequestsOnly {

    @ModelAttribute
    void requireLocalRequest(HttpServletRequest request) {
        if (!isLoopback(request.getRemoteAddr())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Hindsight can only be used from this computer");
        }
    }

    static boolean isLoopback(String address) {
        try {
            return address != null && InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
