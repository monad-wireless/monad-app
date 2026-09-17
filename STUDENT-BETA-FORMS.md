# MonadCount student beta: Google Forms copy

Update, 2026-09-16 (IP-157): the Google Form is retired. Signup is
https://monad.dubec.dev/join, a page the API renders and receives, stored in
`beta_signups` and worked from the admin's onboarding desk (`monad.dubec.dev/join`
is what `web.beta_signup_url` now points at). The copy below is the reference
text that page was written from, kept for the wording; nothing here is live.

Update, 2026-09-14: Jakub created the beta form and supplied
https://forms.gle/BUvgNWEurbyhi5WWA. It was configured as `web.beta_signup_url`.
The feedback form is deferred. The proposed questions below remain reference
copy; the live form's fields could not be checked because the fetch returned 401.

Use the beta form for volunteer invitations; app-store distribution comes later.
The questions and settings below were proposed before the form was created.
The feedback form remains a draft, and no mailing automation has been set up.

## Form 1: MonadCount student beta

### Description to paste

Want to help test the little boxes in the FIIT library?

MonadCount investigates how Wi-Fi and Bluetooth measurements can help us
understand how busy a room is. I’m preparing an invitation-only student beta of
the companion app. App-store access is planned later.

Leave your email if you’d like an invitation when I can include you. You may be
asked to try a short measurement task at FIIT and tell me how the app behaved.
No experience with wireless sensing is needed. Bugs are welcome; they were
probably coming anyway.

This form expresses interest. It does not reserve a place or enrol you in a
research session. An invitation will explain the task, expected time and data
recorded, so you can decide then.

Jakub Dubec, FIIT STU Bratislava · jakub.dubec@stuba.sk

### Questions

| Question                                              | Type                                       | Required | Options / help text                                                                                 |
| ----------------------------------------------------- | ------------------------------------------ | -------- | --------------------------------------------------------------------------------------------------- |
| Email address                                         | Google’s email collection, Responder input | Yes      | Use an address where you would like to receive your invitation. Do not add a second email question. |
| What should I call you?                               | Short answer                               | No       | A first name is enough.                                                                             |
| Which phone would you use?                            | Multiple choice                            | Yes      | Android; iPhone; I’m not sure / I don’t have a suitable phone.                                      |
| Could you join a short session at FIIT in Bratislava? | Multiple choice                            | No       | Yes; Sometimes, depending on the time; Not in person, but I’m interested in testing the app.        |
| Beta invitations                                      | Checkbox, one choice                       | Yes      | Please email me about joining the MonadCount volunteer beta and arranging a test session.           |
| Project updates                                       | Checkbox, one choice, initially unchecked  | No       | I’d also like occasional emails about MonadCount results and future ways to take part.              |

Do not collect student numbers, grades, timetables or phone numbers. Ask for an
exact phone model later if the selected beta build needs it. The newsletter
choice is independent: an unchecked box still permits a beta invitation.

### Contact-data note to paste

Jakub Dubec will use your responses to organise beta invitations and test
sessions. Project updates are sent only if you choose them above. This form and
its responses are stored using Google Forms and Google Sheets. Your email will
not be put into the public research dataset. To leave either list or request
deletion of your signup response, email jakub.dubec@stuba.sk.

### Retention decision before publishing

Choose an actual deletion rule and add it to the contact-data note. Suggested
rule: remove invitation-only contacts three months after the invitation round
closes; keep update subscribers until they unsubscribe, with an annual review.
This is a proposed operating rule, not a claim about an existing process.
Deletion must cover both the Form responses and the linked Sheet.

### Confirmation message

Thanks, your interest is recorded. I’ll email you when I can offer a suitable
place. There is nothing to install yet. Joining this list does not enrol you in
a study. Questions or a change of plans? Write to jakub.dubec@stuba.sk.

### Form settings

Use **Collect email addresses → Responder input**, so the respondent types the
address they want to use. [Google’s email collection instructions](https://support.google.com/docs/answer/139706?hl=en).

Publish the responder link for anyone with the link, leave **Limit to 1 response**
off, and leave **View results summary** off. Google says the one-response setting
requires sign-in, and the summary can expose response text to responders.
[Google’s publishing instructions](https://support.google.com/docs/answer/2839588?hl=en).

Keep form editing and the response Sheet private to the people handling
invitations. Test the responder link in a signed-out browser on a phone. Check
that leaving Project updates unchecked still allows submission. Delete that
test response from both places afterwards.

Google Forms gathers interest; it does not run the newsletter. For the first
small invitation rounds, use individual emails. Maintain invitation status and
the optional update preference in the private response Sheet. Record opt-outs
before another round; do not put the list into a visible To or CC field.

## Connecting the form to the website

Paste the published **responder URL**, not the edit URL, into `config.toml` under
`[web]` as `beta_signup_url`. The supplied beta URL is now configured.

The homepage and `/quests/#beta` share `_beta_invitation.html`. When the URL is
configured, the button reads **Notify me about the beta** and explicitly opens
Google Forms. Without it, the page says the form is being prepared and offers
**Ask about beta testing** by email. There is no embedded form, account creation,
fake submission confirmation or newsletter subscription on the website itself.

The configuration change needs the ordinary web release and service restart.
Do not change the invitation wording to a store download until a public release
actually exists. At that point retain the volunteer-session explanation and
review each QR and quest page’s invitation-only notice.

## Form 2, later: MonadCount beta feedback

Send this only to invited testers after they have tried the app.

**Description:** Tell me what happened while you tried MonadCount. A small
annoyance is worth reporting. Please avoid names of other participants or
personal information in your answer.

| Question | Type | Required |
|---|---|---|
| Which phone and app version did you use? | Short answer | Yes |
| What were you trying to do? | Paragraph | Yes |
| What happened, and what did you expect? | Paragraph | Yes |
| Which part of the instructions was unclear? | Paragraph | No |
| May I email you about this report? If so, leave an address. | Short answer with email validation | No |

**Confirmation:** Thanks. I’ll use this to improve the app and the instructions.
If you left an email, I may follow up for details.

Keep automatic email collection off for this feedback form. Do not require file
uploads or session identifiers. Handle screenshots privately if a particular bug
needs one. Feedback is separate from the study measurements and beta signup.

## Editorial review and remaining detail

The deployed homepage led with corpus totals and six technical result cards.
It said the project already answers the counting question and invited visitors
to use an app they could not obtain. The privacy notice still described BLE as
future work. Jakub confirmed on 2026-09-14 that BLE monitoring has already started.

The revised pages lead with the device explanation, then the beta and student
projects, before research detail. Jokes concern equipment or debugging, not
students’ ability or diligence. The device pages keep their hardware explanation.

The privacy copy now acknowledges active BLE and removes unsupported blanket
anonymity promises. The exact BLE fields, identifier handling and retention
period still need a checked operational account; no values were invented here.
Keep that work separate from the interest form’s contact-data notice.
