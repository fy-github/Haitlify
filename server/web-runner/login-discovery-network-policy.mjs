import dns from 'node:dns/promises'
import net from 'node:net'

export const createLoginDiscoveryNetworkPolicy = (options = {}) => {
  const allowedHosts = normalizeAllowedHosts(options.allowedHosts)
  const allowPrivateNetworks = options.allowPrivateNetworks === true
  const lookup = options.lookup || ((hostname) => dns.lookup(hostname, { all: true, verbatim: true }))

  return {
    async assertAllowedUrl(value) {
      const url = parseHttpUrl(value)
      const hostname = url.hostname.toLowerCase()
      if (matchesAllowedHost(hostname, allowedHosts) || allowPrivateNetworks) return url

      const addresses = net.isIP(hostname)
        ? [{ address: hostname }]
        : await lookup(hostname)
      if (!addresses.length || addresses.some(({ address }) => isPrivateOrReservedAddress(address))) {
        throw new Error(`Login discovery target is blocked by network policy: ${hostname}`)
      }
      return url
    }
  }
}

const parseHttpUrl = (value) => {
  let url
  try {
    url = new URL(String(value || ''))
  } catch {
    throw new Error('A valid HTTP login URL is required')
  }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) {
    throw new Error('A valid HTTP login URL without credentials is required')
  }
  return url
}

const normalizeAllowedHosts = (value) => String(value || '')
  .split(',')
  .map((host) => host.trim().toLowerCase())
  .filter(Boolean)

const matchesAllowedHost = (hostname, allowedHosts) => allowedHosts.some((allowedHost) =>
  allowedHost === hostname
  || (allowedHost.startsWith('*.') && hostname.endsWith(allowedHost.slice(1)))
)

const isPrivateOrReservedAddress = (address) => {
  const normalized = String(address).toLowerCase()
  if (normalized.startsWith('::ffff:')) {
    return isPrivateOrReservedAddress(normalized.slice('::ffff:'.length))
  }
  if (net.isIP(normalized) === 4) return isPrivateOrReservedIpv4(normalized)
  if (net.isIP(normalized) === 6) {
    return normalized === '::'
      || normalized === '::1'
      || normalized.startsWith('fc')
      || normalized.startsWith('fd')
      || /^fe[89ab]/.test(normalized)
      || normalized.startsWith('ff')
  }
  return true
}

const isPrivateOrReservedIpv4 = (address) => {
  const [first, second] = address.split('.').map(Number)
  return first === 0
    || first === 10
    || first === 127
    || (first === 100 && second >= 64 && second <= 127)
    || (first === 169 && second === 254)
    || (first === 172 && second >= 16 && second <= 31)
    || (first === 192 && second === 0)
    || (first === 192 && second === 168)
    || (first === 198 && (second === 18 || second === 19))
    || first >= 224
}
