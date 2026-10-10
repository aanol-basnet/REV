import { networkInterfaces, type NetworkInterfaceInfo } from 'node:os';

export type Interfaces = NodeJS.Dict<NetworkInterfaceInfo[]>;

/** Virtual interfaces that aren't the home network: containers, VPNs (Tailscale), bridges. */
const VIRTUAL =
  /^(docker|br-|veth|virbr|tailscale|tun|tap|wg|zt|lxc|lxd|podman|cni|flannel|vmnet|vboxnet|utun)/i;

/**
 * The addresses at which this REV server can be reached on the local network right now, e.g.
 * `http://192.168.1.69:4600`. Read from the network interfaces on every call, so a new DHCP lease
 * is picked up without a restart. Empty when REV listens only on loopback or a VPN address.
 */
export function lanUrls(
  host: string,
  port: number,
  interfaces: Interfaces = networkInterfaces(),
): string[] {
  const candidates = Object.entries(interfaces)
    .filter(([name]) => !VIRTUAL.test(name))
    .flatMap(([, infos]) => infos ?? [])
    .filter((i) => i.family === 'IPv4' && !i.internal && isPrivateIpv4(i.address))
    .map((i) => i.address);
  const listensEverywhere = host === '0.0.0.0' || host === '::' || host === '';
  const addresses = listensEverywhere ? candidates : candidates.filter((a) => a === host);
  return [...new Set(addresses)].map((a) => `http://${a}:${port}`);
}

export function isPrivateIpv4(address: string): boolean {
  const [a, b] = address.split('.').map(Number);
  if (a === undefined || b === undefined) return false;
  return a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168);
}
