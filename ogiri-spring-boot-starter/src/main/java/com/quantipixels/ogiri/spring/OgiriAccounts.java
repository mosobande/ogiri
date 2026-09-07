// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Quanti Pixels
package com.quantipixels.ogiri.spring;


import com.quantipixels.ogiri.Subject;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;

/** Application-owned mapping between login authentication and stable, fully scoped identity. */
public interface OgiriAccounts {
    Subject subject(Authentication authentication);
    UserDetails load(Subject subject);
}
