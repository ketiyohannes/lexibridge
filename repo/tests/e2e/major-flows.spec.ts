import { expect, test } from '@playwright/test'

const pause = async (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

test('major portal flows walkthrough (recorded, human-visible speed)', async ({ page }) => {
  test.setTimeout(12 * 60 * 1000)

  await page.goto('/login')
  await expect(page.getByRole('heading', { name: 'LexiBridge Operations Suite' })).toBeVisible()
  await pause(7000)

  await page.getByLabel('Username').fill('admin')
  await page.getByLabel('Password').fill('AdminPass2026!')
  await pause(4000)
  await page.getByRole('button', { name: 'Sign In' }).click()

  await expect(page).toHaveURL(/\/portal$/)
  await expect(page.getByRole('heading', { name: 'LexiBridge Portal' })).toBeVisible()
  await pause(8000)
  await pause(12000)

  await page.getByRole('link', { name: 'Content' }).first().click()
  await expect(page.getByRole('heading', { name: 'Content Publishing Module' })).toBeVisible()
  await pause(7000)

  const seed = Date.now()
  await page.locator('input[name="term"]').first().fill(`playwright-term-${seed}`)
  await page.locator('input[name="category"]').first().fill('VOCABULARY')
  await page.locator('textarea[name="definitionText"]').first().fill('Playwright end-to-end definition for local walkthrough')
  await page.locator('textarea[name="exampleSentence"]').first().fill('This sentence is entered by Playwright for the demo flow.')
  await pause(5000)
  await page.getByRole('button', { name: 'Create Draft' }).click()
  await expect(page.getByRole('heading', { name: 'Publish Workflow Queue' })).toBeVisible()
  await pause(9000)

  await page.goto('/portal/bookings')
  await expect(page.getByRole('heading', { name: 'Booking and Attendance Module' })).toBeVisible()
  await pause(7000)

  await page.locator('input[name="customerName"]').first().fill('Playwright Demo Customer')
  await page.locator('input[name="customerPhone"]').first().fill('555-0102')
  await page.locator('textarea[name="orderNote"]').first().fill('Recorded E2E walkthrough reservation')
  await pause(5000)
  await page.getByRole('button', { name: 'Reserve' }).click()
  await pause(9000)

  const bookingBody = await page.textContent('body')
  const bookingIdMatch = bookingBody?.match(/Booking #(\d+)/)
  const bookingId = bookingIdMatch ? bookingIdMatch[1] : '1'

  await page.goto('/portal/leave')
  await expect(page.getByRole('heading', { name: 'Leave Workflow Module' })).toBeVisible()
  await pause(7000)

  await page.locator('textarea[name="formReason"]').first().fill('Playwright local walkthrough leave request')
  await pause(4000)
  await page.getByRole('button', { name: 'Submit Request' }).click()
  await pause(7000)

  const loadRecentRequestsButton = page.getByRole('button', { name: 'Load Recent Requests' })
  if (await loadRecentRequestsButton.count()) {
    await loadRecentRequestsButton.first().click()
    await pause(8000)
  }

  await page.goto('/portal/payments')
  await expect(page.getByRole('heading', { name: 'Payments and Reconciliation Module' })).toBeVisible()
  await pause(7000)

  await page.locator('input[name="bookingOrderId"]').first().fill(bookingId)
  await page.locator('input[name="amount"]').first().fill('25.00')
  await page.locator('input[name="currency"]').first().fill('USD')
  await page.locator('input[name="terminalId"]').first().fill('TERM-PLAYWRIGHT-01')
  await page.locator('input[name="terminalTxnId"]').first().fill(`TXN-${seed}`)
  await pause(5000)
  await page.getByRole('button', { name: 'Create Tender' }).click()
  await pause(8000)

  await page.getByRole('button', { name: 'Run Reconciliation' }).click()
  await pause(7000)

  await page.goto('/portal/admin')
  await expect(page.getByRole('heading', { name: 'Admin Module' })).toBeVisible()
  await pause(7000)

  await page.locator('input[name="username"]').first().fill(`pw-user-${seed}`)
  await page.locator('input[name="fullName"]').first().fill('Playwright Demo User')
  await page.locator('input[name="email"]').first().fill(`pw-user-${seed}@example.local`)
  await page.locator('input[name="password"]').first().fill('TempPass2026!')
  await page.locator('input[name="rolesCsv"]').first().fill('EMPLOYEE')
  await pause(5000)
  await page.getByRole('button', { name: 'Create User' }).click()
  await pause(9000)

  await page.goto('/portal/moderation')
  await expect(page.getByRole('heading', { name: 'Moderation Inbox Module' })).toBeVisible()
  await pause(9000)

  const openReview = page.getByRole('link', { name: 'Open review' }).first()
  if (await openReview.count()) {
    await openReview.click()
    await pause(7000)
    await page.goto('/portal/moderation')
  }

  await page.goto('/portal')
  await expect(page.getByRole('heading', { name: 'LexiBridge Portal' })).toBeVisible()
  await pause(10000)
  await pause(12000)

  await page.getByRole('button', { name: 'Logout' }).click()
  await expect(page).toHaveURL(/\/login/)
  await pause(5000)
})
