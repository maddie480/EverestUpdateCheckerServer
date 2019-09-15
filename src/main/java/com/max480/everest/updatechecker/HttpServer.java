package com.max480.everest.updatechecker;

import fi.iki.elonen.NanoHTTPD;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class HttpServer extends NanoHTTPD {
    private static final Logger log = LoggerFactory.getLogger(HttpServer.class);

    public HttpServer(int port) {
        super(port);
    }

    @Override
    public Response serve(IHTTPSession session) {
        log.debug(session.getMethod() + " " + session.getUri());

        if (!session.getUri().equals("/everestupdate.yaml")) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=UTF-8", "Not Found");
        }

        if (session.getMethod() == Method.GET) {
            try {
                return newChunkedResponse(Response.Status.OK, "text/yaml", new FileInputStream("uploads/everestupdate.yaml"));
            } catch (IOException e) {
                log.error("Error while reading everestupdate.yaml", e);
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Unexpected error.");
            }
        } else if (session.getMethod() == Method.POST) {
            try {
                if (!session.getHeaders().containsKey("authorization")) {
                    return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Authentication required.");
                } else if (!session.getHeaders().get("authorization").equals(IOUtils.toString(new FileInputStream("code.txt"), "UTF-8").trim())) {
                    return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "Access denied.");
                } else {
                    int contentLength = Integer.parseInt(session.getHeaders().get("content-length"));
                    byte[] buffer = new byte[contentLength];
                    int readBytes = session.getInputStream().read(buffer, 0, contentLength);
                    if (readBytes != contentLength) {
                        throw new IOException("Could not read whole request body: " + readBytes + "/" + contentLength);
                    }

                    FileUtils.writeByteArrayToFile(new File("uploads/everestupdate.yaml"), buffer);
                    return newFixedLengthResponse(Response.Status.OK, "text/plain", "OK");
                }
            } catch (IOException e) {
                log.error("Error while updating everestupdate.yaml", e);
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Unexpected error.");
            }
        } else {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Method not supported.");
        }
    }
}
