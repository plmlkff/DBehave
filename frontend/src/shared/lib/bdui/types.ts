export type FieldType = 'INTEGER' | 'NUMBER' | 'BOOLEAN' | 'STRING' | 'ENUM'
export type FieldValue = string | number | boolean
export type FieldValues = Record<string, FieldValue>
export type FieldErrors = Record<string, string>

export type FieldSpec = {
  name: string
  title: string
  description: string
  type: FieldType
  defaultValue: FieldValue
  min: number | null
  max: number | null
  unit: string | null
  options: string[]
}
