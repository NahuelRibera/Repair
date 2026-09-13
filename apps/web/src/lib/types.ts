export function vehicleTitle(manufacturerName: string, modelName: string): string {
  return modelName.toLowerCase().startsWith(manufacturerName.toLowerCase())
    ? modelName
    : `${manufacturerName} ${modelName}`;
}

export interface PageResult<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface Manufacturer {
  id: number;
  name: string;
  slug: string;
}

export interface VehicleModel {
  id: number;
  manufacturerId: number;
  modelName: string;
  slug: string;
}

export interface VehicleVariant {
  id: number;
  modelId: number;
  variantName: string;
  slug: string;
  yearStart: number | null;
  yearEnd: number | null;
  fuelType: string | null;
  driveType: string | null;
  gearbox: string | null;
  qualityStatus: string;
}

export interface Specs {
  powerHp: number | null;
  powerKw: number | null;
  powerBhp: number | null;
  torqueNm: number | null;
  torqueLbft: number | null;
  topSpeedKmh: number | null;
  acceleration0100KmhS: number | null;
  displacementCm3: number | null;
  weightKg: number | null;
  cylinderLayout: string | null;
  cylinderCount: number | null;
  co2EmissionsGKm: number | null;
  fuelConsumptionCombinedL100km: number | null;
}

export interface VariantDetail {
  variant: VehicleVariant;
  manufacturerName: string;
  modelName: string;
  specs: Specs;
  hasKnowledgeCoverage: boolean;
}

export interface CoveredVehicle {
  variantId: number;
  manufacturerName: string;
  modelName: string;
  variantName: string;
  yearStart: number | null;
  yearEnd: number | null;
}

export interface SessionSummary {
  id: number;
  title: string | null;
  variantId: number;
  manufacturerName: string;
  modelName: string;
  variantName: string;
  createdAt: string;
  updatedAt: string;
}

export interface Message {
  id: number;
  role: "user" | "assistant" | "system";
  content: string;
  structuredResponseJson: string | null;
  createdAt: string;
}

export interface SessionDetail {
  session: SessionSummary;
  messages: Message[];
}

export type AnswerType = "clarification" | "guidance" | "insufficient_evidence" | "safety_referral";

export interface Hypothesis {
  description: string;
  reasoning: string;
  evidenceChunkIds: number[];
}

export interface DiagnosticAnswer {
  answerType: AnswerType;
  summary: string;
  confirmedSymptoms: string[];
  followUpQuestions: string[];
  hypotheses: Hypothesis[];
  safeChecks: string[];
  cautions: string[];
  missingInformation: string[];
  sourceChunkIds: number[];
}

export interface EvidenceCard {
  chunkId: number;
  documentId: number;
  documentTitle: string;
  documentProvenance: string;
  documentSourceUrl: string | null;
  heading: string | null;
  sectionPath: string | null;
  pageNumber: number | null;
  excerpt: string;
  vectorScore: number | null;
  textScore: number | null;
  fusedScore: number;
}

export interface RagRunDebug {
  requestId: string;
  variantId: number;
  embeddingModel: string | null;
  generationModel: string | null;
  promptTokens: number | null;
  completionTokens: number | null;
  retrievalMillis: number | null;
  generationMillis: number | null;
  providerStatus: string;
  errorDetail: string | null;
}

export interface ChatTurnResult {
  messageId: number;
  answer: DiagnosticAnswer;
  evidence: EvidenceCard[];
  debug: RagRunDebug;
}

export interface DataQualitySummary {
  manufacturers: number;
  models: number;
  variants: number;
  rawRecords: number;
  totalIssues: number;
  issuesByRule: { key: string; count: number }[];
  issuesBySeverity: { key: string; count: number }[];
}

export interface DataQualityIssue {
  id: number;
  rule: string;
  field: string | null;
  observedValuePreview: string | null;
  severity: string;
  explanation: string;
  manufacturerName: string | null;
  modelName: string | null;
  variantName: string | null;
}
