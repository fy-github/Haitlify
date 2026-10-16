import { describe, expect, it } from 'vitest'
import {
  buildFigmaNodeTree,
  figmaDraftEvidenceLabel,
  groupFigmaElementCandidates,
  sortFigmaChangeRows
} from './aiFigmaRequirementView'

describe('aiFigmaRequirementView', () => {
  it('groups Figma nodes by hierarchy', () => {
    const tree = buildFigmaNodeTree([
      { figmaNodeId: 'figma-node-1', nodeId: '1:1', nodeName: 'Login Page', nodeType: 'FRAME', parentNodeId: '', componentName: '' },
      { figmaNodeId: 'figma-node-2', nodeId: '1:2', nodeName: 'Email Input', nodeType: 'INSTANCE', parentNodeId: '1:1', componentName: 'Input / Text Field' },
      { figmaNodeId: 'figma-node-3', nodeId: '1:3', nodeName: 'Submit Button', nodeType: 'INSTANCE', parentNodeId: '1:1', componentName: 'Button / Primary' }
    ])

    expect(tree).toEqual([
      {
        id: '1:1',
        label: 'Login Page',
        type: 'FRAME',
        componentName: '',
        children: [
          { id: '1:2', label: 'Email Input', type: 'INSTANCE', componentName: 'Input / Text Field', children: [] },
          { id: '1:3', label: 'Submit Button', type: 'INSTANCE', componentName: 'Button / Primary', children: [] }
        ]
      }
    ])
  })

  it('formats draft evidence label with file key and node name', () => {
    expect(figmaDraftEvidenceLabel({
      evidence: {
        sourceType: 'figma',
        fileKey: 'FileKey123',
        nodeId: '1:2',
        nodeName: 'Submit Button',
        nodeType: 'INSTANCE',
        componentName: 'Button / Primary'
      }
    })).toBe('FileKey123 · Submit Button · Button / Primary')
  })

  it('sorts change rows by added changed removed priority', () => {
    expect(sortFigmaChangeRows([
      { changeType: 'removed', nodeId: '1:4', nodeName: 'Old Card', changedFields: [] },
      { changeType: 'changed', nodeId: '1:3', nodeName: 'Styled Card', changedFields: ['style'] },
      { changeType: 'added', nodeId: '1:5', nodeName: 'New Toggle', changedFields: [] }
    ])).toEqual([
      { changeType: 'added', nodeId: '1:5', nodeName: 'New Toggle', changedFields: [] },
      { changeType: 'changed', nodeId: '1:3', nodeName: 'Styled Card', changedFields: ['style'] },
      { changeType: 'removed', nodeId: '1:4', nodeName: 'Old Card', changedFields: [] }
    ])
  })

  it('groups element candidates by component', () => {
    expect(groupFigmaElementCandidates([
      { candidateId: 'candidate-1', componentKey: 'button-primary', componentName: 'Button / Primary', nodeId: '1:2', nodeName: 'Submit Button', role: 'button', suggestedLocatorName: 'button-primary', status: 'pending' },
      { candidateId: 'candidate-2', componentKey: 'button-primary', componentName: 'Button / Primary', nodeId: '2:2', nodeName: 'Submit Button Mobile', role: 'button', suggestedLocatorName: 'button-primary', status: 'confirmed' },
      { candidateId: 'candidate-3', componentKey: 'input-email', componentName: 'Input / Text Field', nodeId: '1:3', nodeName: 'Email Input', role: 'textbox', suggestedLocatorName: 'input-email', status: 'pending' }
    ])).toEqual([
      {
        key: 'button-primary',
        componentName: 'Button / Primary',
        role: 'button',
        pendingCount: 1,
        confirmedCount: 1,
        items: [
          { candidateId: 'candidate-1', componentKey: 'button-primary', componentName: 'Button / Primary', nodeId: '1:2', nodeName: 'Submit Button', role: 'button', suggestedLocatorName: 'button-primary', status: 'pending' },
          { candidateId: 'candidate-2', componentKey: 'button-primary', componentName: 'Button / Primary', nodeId: '2:2', nodeName: 'Submit Button Mobile', role: 'button', suggestedLocatorName: 'button-primary', status: 'confirmed' }
        ]
      },
      {
        key: 'input-email',
        componentName: 'Input / Text Field',
        role: 'textbox',
        pendingCount: 1,
        confirmedCount: 0,
        items: [
          { candidateId: 'candidate-3', componentKey: 'input-email', componentName: 'Input / Text Field', nodeId: '1:3', nodeName: 'Email Input', role: 'textbox', suggestedLocatorName: 'input-email', status: 'pending' }
        ]
      }
    ])
  })
})
