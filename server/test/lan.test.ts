import { afterEach, describe, expect, it } from 'vitest';
import type { NetworkInterfaceInfo } from 'node:os';
import { CastToken, ServerAddresses } from '@rev/schemas';
import { type Interfaces, lanUrls } from '../src/system/lan.js';
import { createTestApp, signedInHeaders, type TestContext } from './helpers.js';

const v4 = (address: string, internal = false): NetworkInterfaceInfo => ({
  address,
  netmask: '255.255.255.0',
  family: 'IPv4',
  mac: '00:00:00:00:00:00',
  internal,
  cidr: `${address}/24`,
});

const machine: Interfaces = {
  lo: [v4('127.0.0.1', true)],
  enp8s0: [v4('192.168.1.69')],
  tailscale0: [v4('100.64.0.7')],
  docker0: [v4('172.17.0.1')],
  wlan0: [v4('10.0.0.5')],
};

describe('LAN addresses', () => {
  it('lists private addresses of real interfaces, skipping loopback, VPNs and containers', () => {
    expect(lanUrls('0.0.0.0', 4600, machine)).toEqual([
      'http://192.168.1.69:4600',
      'http://10.0.0.5:4600',
    ]);
  });

  it('only offers the address REV is bound to, and nothing when that is loopback or a VPN', () => {
    expect(lanUrls('192.168.1.69', 4600, machine)).toEqual(['http://192.168.1.69:4600']);
    expect(lanUrls('127.0.0.1', 4600, machine)).toEqual([]);
    expect(lanUrls('100.64.0.7', 4600, machine)).toEqual([]);
  });
});

describe('addresses endpoint and casting', () => {
  let t: TestContext;
  afterEach(async () => {
    await t.close();
  });

  it('tells signed-in users the current LAN address, which follows a new lease', async () => {
    let current = machine;
    t = await createTestApp({ host: '0.0.0.0', networkInterfaces: () => current });
    const headers = await signedInHeaders(t);

    expect((await t.app.inject('/api/system/addresses')).statusCode).toBe(401);
    const first = ServerAddresses.parse(
      (await t.app.inject({ url: '/api/system/addresses', headers })).json(),
    );
    expect(first.lanUrls[0]).toBe('http://192.168.1.69:4600');

    current = { ...machine, enp8s0: [v4('192.168.1.42')] };
    const moved = ServerAddresses.parse(
      (await t.app.inject({ url: '/api/system/addresses', headers })).json(),
    );
    expect(moved.lanUrls[0]).toBe('http://192.168.1.42:4600');

    // Casting follows it too, unless a cast address is configured.
    const cast = await t.app.inject({ method: 'POST', url: '/api/cast/token', headers });
    expect(CastToken.parse(cast.json()).baseUrl).toBe('http://192.168.1.42:4600');
  });

  it('prefers a configured address', async () => {
    t = await createTestApp({
      host: '0.0.0.0',
      lanUrl: 'http://nas.lan:4600',
      networkInterfaces: () => machine,
    });
    const headers = await signedInHeaders(t);
    const res = ServerAddresses.parse(
      (await t.app.inject({ url: '/api/system/addresses', headers })).json(),
    );
    expect(res.lanUrls).toEqual(['http://nas.lan:4600']);
  });
});
