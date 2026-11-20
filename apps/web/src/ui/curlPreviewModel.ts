import type { ExecutableRequest } from '../domain/apiExecution'

export interface CurlPreviewDrawerModel {
  curlVisible: boolean
  copyCurl: () => Promise<void>
  executableRequest: ExecutableRequest | undefined
  curlText: string
}
