// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest';

const account = vi.hoisted(() => ({ readSecret: vi.fn(async (): Promise<string | null> => null), write: vi.fn(async () => undefined) }));
vi.mock('./accountSync', () => account);

describe('keeping the key in the YouTube account', () => {
  beforeEach(() => {
    vi.resetModules();
    localStorage.clear();
    account.readSecret.mockReset();
    account.readSecret.mockResolvedValue(null);
    account.write.mockClear();
  });

  it('only looks before a yes, asks when the account holds no key, and writes after the yes', async () => {
    const { syncAccount, setAccountSync, useConnect } = await import('./connect');
    expect(useConnect.getState().saved.accountSync).toBeNull();
    await syncAccount();
    expect(account.readSecret).toHaveBeenCalled();
    expect(account.write).not.toHaveBeenCalled();
    expect(useConnect.getState().askAccount).toBe(true);

    setAccountSync(false);
    expect(useConnect.getState().askAccount).toBe(false);
    account.readSecret.mockClear();
    await syncAccount();
    expect(account.readSecret).not.toHaveBeenCalled();
    expect(account.write).not.toHaveBeenCalled();

    setAccountSync(true);
    await vi.waitFor(() => expect(account.write).toHaveBeenCalled());
  });

  it('joins without asking when the account already holds a key', async () => {
    const secret = 'A'.repeat(43);
    account.readSecret.mockResolvedValue(secret);
    const { syncAccount, useConnect } = await import('./connect');
    await syncAccount();
    expect(useConnect.getState().saved.accountSync).toBe(true);
    expect(useConnect.getState().askAccount).toBe(false);
    expect(useConnect.getState().saved.secret).toBe(secret);
    expect(account.write).not.toHaveBeenCalled();
  });

  it('asks nothing yet when the account cannot be read', async () => {
    account.readSecret.mockRejectedValue(new Error('signed out'));
    const { syncAccount, useConnect } = await import('./connect');
    await syncAccount();
    expect(useConnect.getState().saved.accountSync).toBeNull();
    expect(useConnect.getState().askAccount).toBe(false);
  });
});
