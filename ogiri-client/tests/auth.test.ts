import { describe, it, expect, vi } from "vitest";
import { OgiriAuth } from "../src/auth";
import { OgiriAuthError } from "../src/errors";
import type { OgiriTokens } from "../src/types";

const mockTokens: OgiriTokens = {
    accessToken: "test-token",
    client: "test-client",
    uid: "user@example.com",
    expiry: "1234567890",
    tokenType: "Bearer",
};

describe("OgiriAuth", () => {
    describe("token state", () => {
        it("should start unauthenticated", () => {
            const auth = new OgiriAuth();
            expect(auth.isAuthenticated()).toBe(false);
            expect(auth.getTokens()).toBeNull();
        });

        it("should set and get tokens", () => {
            const auth = new OgiriAuth();
            auth.setTokens(mockTokens);
            expect(auth.isAuthenticated()).toBe(true);
            expect(auth.getTokens()).toEqual(mockTokens);
        });

        it("should clear tokens", () => {
            const auth = new OgiriAuth();
            auth.setTokens(mockTokens);
            auth.clearTokens();
            expect(auth.isAuthenticated()).toBe(false);
            expect(auth.getTokens()).toBeNull();
        });
    });

    describe("subscribe", () => {
        it("should notify listeners on setTokens", () => {
            const auth = new OgiriAuth();
            const listener = vi.fn();
            auth.subscribe(listener);

            auth.setTokens(mockTokens);
            expect(listener).toHaveBeenCalledTimes(1);
        });

        it("should notify listeners on clearTokens", () => {
            const auth = new OgiriAuth();
            auth.setTokens(mockTokens);
            const listener = vi.fn();
            auth.subscribe(listener);

            auth.clearTokens();
            expect(listener).toHaveBeenCalledTimes(1);
        });

        it("should stop notifying after unsubscribe", () => {
            const auth = new OgiriAuth();
            const listener = vi.fn();
            const unsub = auth.subscribe(listener);

            auth.setTokens(mockTokens);
            expect(listener).toHaveBeenCalledTimes(1);

            unsub();
            auth.clearTokens();
            expect(listener).toHaveBeenCalledTimes(1);
        });
    });

    describe("injectInto", () => {
        it("should return config unchanged when no tokens", () => {
            const auth = new OgiriAuth();
            const config: RequestInit = { method: "GET" };
            const result = auth.injectInto(config);
            expect(result).toBe(config);
        });

        it("should inject auth headers when tokens exist", () => {
            const auth = new OgiriAuth({ authMethod: "headers" });
            auth.setTokens(mockTokens);

            const result = auth.injectInto({});
            const headers = result.headers as Record<string, string>;

            expect(headers["access-token"]).toBe("test-token");
            expect(headers.client).toBe("test-client");
            expect(headers.uid).toBe("user@example.com");
            expect(headers.expiry).toBe("1234567890");
            expect(headers["token-type"]).toBe("Bearer");
        });

        it("should support bearer auth method", () => {
            const auth = new OgiriAuth({ authMethod: "bearer" });
            auth.setTokens(mockTokens);

            const result = auth.injectInto({});
            const headers = result.headers as Record<string, string>;
            expect(headers.Authorization).toMatch(/^Bearer /);
        });
    });

    describe("extractFrom", () => {
        it("should extract and store rotated tokens from response", () => {
            const auth = new OgiriAuth();
            const response = new Response(null, {
                headers: new Headers({
                    "access-token": "rotated-token",
                    client: "rotated-client",
                    uid: "user@example.com",
                    expiry: "9999999999",
                    "token-type": "Bearer",
                }),
            });

            auth.extractFrom(response);

            expect(auth.getTokens()).toEqual({
                accessToken: "rotated-token",
                client: "rotated-client",
                uid: "user@example.com",
                expiry: "9999999999",
                tokenType: "Bearer",
            });
        });

        it("should not update tokens when headers are missing", () => {
            const auth = new OgiriAuth();
            auth.setTokens(mockTokens);
            const response = new Response(null);

            auth.extractFrom(response);
            expect(auth.getTokens()).toEqual(mockTokens);
        });

        it("should notify listeners when tokens rotate", () => {
            const auth = new OgiriAuth();
            const listener = vi.fn();
            auth.subscribe(listener);

            const response = new Response(null, {
                headers: new Headers({
                    "access-token": "new-token",
                    client: "new-client",
                    uid: "user@example.com",
                    expiry: "9999999999",
                    "token-type": "Bearer",
                }),
            });

            auth.extractFrom(response);
            expect(listener).toHaveBeenCalledTimes(1);
        });
    });

    describe("handleAuthError", () => {
        it("should clear tokens and return OgiriAuthError", () => {
            const auth = new OgiriAuth();
            auth.setTokens(mockTokens);

            const error = auth.handleAuthError({ error: "Unauthorized" });

            expect(auth.isAuthenticated()).toBe(false);
            expect(error).toBeInstanceOf(OgiriAuthError);
            expect(error.status).toBe(401);
            expect(error.body).toEqual({ error: "Unauthorized" });
        });

        it("should fire onAuthError callback", () => {
            const onAuthError = vi.fn();
            const auth = new OgiriAuth({ onAuthError });
            auth.setTokens(mockTokens);

            auth.handleAuthError(null);

            expect(onAuthError).toHaveBeenCalledWith(expect.any(OgiriAuthError));
        });
    });

    describe("headerInjector", () => {
        it("should return a function that adds auth headers", () => {
            const auth = new OgiriAuth({ authMethod: "headers" });
            auth.setTokens(mockTokens);

            const inject = auth.headerInjector();
            const result = inject({ "Content-Type": "application/json" });

            expect(result["Content-Type"]).toBe("application/json");
            expect(result["access-token"]).toBe("test-token");
            expect(result.client).toBe("test-client");
        });

        it("should return original headers when unauthenticated", () => {
            const auth = new OgiriAuth();
            const inject = auth.headerInjector();
            const headers = { "Content-Type": "application/json" };
            const result = inject(headers);

            expect(result).toEqual(headers);
        });
    });
});
