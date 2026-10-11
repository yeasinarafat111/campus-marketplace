# Demo script (about 5 minutes)

Payments run in Stripe **test mode**: no real money moves. On Stripe's page click **Pay without Link** if it
offers it, then use card `4242 4242 4242 4242`, any future expiry (for example `12 / 34`), any CVC and any name
or postcode.

**Before you start:** have three browser windows or profiles ready, logged in as a **seller**, a **buyer** and an
**admin**. Keep the seller and buyer logged in so you can switch quickly. Use the demo accounts you created; their
passwords are not in this repo. The API sleeps when idle, so open the site a minute before you present.

| # | Step | What to do | If the network is slow |
|---|------|-----------|------------------------|
| 1 | **Sign up with OTP** (30 s) | Open the site logged out, **Sign up**, enter a name, a `@seu.edu.bd` email, a phone such as `01712345678` and a password. Enter the 6-digit code from the email, then log in. | Use a pre-made account and just show the signup form and the "Only @seu.edu.bd students" line. If the email is slow, **Resend code**. |
| 2 | **Browse** (30 s) | Show the Featured row, category chips with counts, the search box, a price filter, and open a listing (photo, seller card, safety tips). | Use the pre-loaded home page; skip filters. |
| 3 | **Reserve, then Buy now, then confirm receipt** (90 s) | As the **buyer**: open an item, **Reserve** (read the terms, tick "I understand"), pay the small deposit on Stripe. Back on the item you see "Reserved by you" and the seller's phone. Click **Pay online** and pay the balance (price minus deposit). Open **Purchases** and click **Confirm I received it**. | After paying, the page waits up to 15 s for the confirmation; if it says "it may take a minute", reload the listing. Have a **second item already reserved** to start from step "Pay online". |
| 4 | **Cash at meetup** (45 s) | As the **buyer**: reserve another item, click **Pay cash at meetup** (shows the cash amount, seller phone and meetup spot), **I'll pay cash**. As the **seller**: open the item, **Confirm handover and cash received**. Both see a completed cash order. | Skip the Stripe deposit by using an item that is already reserved by the buyer. |
| 5 | **Chat in two windows** (45 s) | Buyer: **Message seller**, send a message. Seller (other window, no reload): the unread badge on the chat icon updates. Open it, reply; the buyer sees the reply and **Seen**. | If a message doesn't appear live, reload that window: chat catches up from the server. |
| 6 | **Feature a listing** (45 s) | As the **seller**: open one of your listings, **Feature for 7 days**, pay on Stripe. The Featured badge appears and the item moves to the top of the home page. | Show the quoted fee on the button and the Stripe page, then continue from a listing that is already featured. |
| 6b | **Ask AI** (30 s) | As the **buyer**: open a listing with a clear product photo, tap **Ask AI**, then **Full specs**. Type "CSE coursework" in "What will you use it for?" and run it again. Ask one question in Bangla, e.g. "এটার ব্যাটারি কেমন?". Tap **Search Bikroy** under an answer to show live prices. | Answers take 5 to 20 s. If the panel says "Out of tokens, please contact admin", the free Gemini quota is used up: show the **Check live prices** buttons instead, which still work. |
| 7 | **Admin revenue** (45 s) | As the **admin**: open **Revenue** from the user menu. Point out net revenue, revenue per source, "owed to sellers", the stacked chart, the history table and **Export CSV**. Mention it is before Stripe fees. Then show the problem-reports queue on the Admin page. | The page works with whatever data exists; switch the range to **Last 90 days**. |

**Talking points:** the AI assistant sends Google only the product photo and text (no names or phones), and says
when it isn't sure; fees are computed on the server; the deposit counts toward the price; seller cancel refunds
the deposit while release or expiry keeps it; orders auto-complete after 72 hours unless the buyer reports a problem;
cash sales owe the seller only the deposit.

**If something breaks mid-demo:** reload the page (state is on the server), and check
`<API>/actuator/health` shows `{"status":"UP"}`.
