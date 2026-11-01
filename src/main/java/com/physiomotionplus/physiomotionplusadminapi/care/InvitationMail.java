package com.physiomotionplus.physiomotionplusadminapi.care;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class InvitationMail {
  private final ObjectProvider<JavaMailSender> mail;
  private final String from;

  public InvitationMail(
      ObjectProvider<JavaMailSender> mail, @Value("${care.mail-from:}") String from) {
    this.mail = mail;
    this.from = from;
  }

  boolean send(String email, String url) {
    var sender = mail.getIfAvailable();
    if (sender == null || from.isBlank()) return false;
    try {
      var message = new SimpleMailMessage();
      message.setFrom(from);
      message.setTo(email);
      message.setSubject("Physiomotion Plus — invitation");
      message.setText(
          "You have been invited to connect with your physiotherapist. Sign in with this email"
              + " address and accept the invitation within seven days:\n\n"
              + url
              + "\n\n"
              + "Vous avez reçu une invitation de votre physiothérapeute. Connectez-vous avec cette"
              + " adresse courriel et acceptez l’invitation dans les sept jours.\n\n"
              + "If you were not expecting this invitation, you can ignore it. / Si vous"
              + " n’attendiez pas cette invitation, vous pouvez l’ignorer.");
      sender.send(message);
      return true;
    } catch (Exception e) {
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn(
              "Invitation email could not be sent; the authorized physio can copy the invitation"
                  + " link.");
      return false;
    }
  }
}
