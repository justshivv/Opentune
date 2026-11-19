// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest';

const account = vi.hoisted(() => ({ readSecret: vi.fn(async () => null), write: vi.fn(async () => undefined) }));
vi.mock('./accountSync', () => account);

describe('keeping the key in the YouTube account', () => {
  beforeEach(() => {
    account.readSecret.mockClear();
    account.write.mockClear();
  });

  it('asks first, and touches nothing in the account before a yes', async () => {
    const { syncAccount, setAccountSync, useConnect } = await import('./connect');
    expect(useConnect.getState().saved.accountSync).toBeNull();
    await syncAccount();
    expect(account.readSecret).not.toHaveBeenCalled();
    expect(account.write).not.toHaveBeenCalled();

    setAccountSync(false);
    await syncAccount();
    expect(account.write).not.toHaveBeenCalled();

    setAccountSync(true);
    await vi.waitFor(() => expect(account.write).toHaveBeenCalled());
  });
});
