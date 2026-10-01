package com.physiomotionplus.physiomotionplusadminapi.security.firebase;

import com.google.firebase.auth.FirebaseToken;
import java.util.Map;

public record FirebasePrincipal(String uid, String email, String displayName,
                                Map<String, Object> claims, FirebaseToken token) { }
