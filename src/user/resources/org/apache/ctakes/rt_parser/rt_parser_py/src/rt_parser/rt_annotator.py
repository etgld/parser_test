import sys

from ctakes_pbj.component.cas_annotator import *
from ctakes_pbj.pbj_tools.create_type import *
from ctakes_pbj.type_system.ctakes_types import *
from cnlpt.cnlp_pipeline_utils import (
    model_dicts,
    classify_casoid_annotations,
    casoid_to_label_tuples,
    generate_paragraph_casoids,
)

from cnlpt.CnlpModelForClassification import (
    CnlpModelForClassification,
    CnlpConfig,
)

from transformers import AutoConfig, AutoModel
from collections import defaultdict


def ctakes_tokenize(cas, sentence):
    return sorted(cas.select_covered(BaseToken, sentence), key=lambda t: t.begin)


def ctakes_clean(cas, sentence):
    base_tokens = []
    token_map = []
    newline_tokens = cas.select_covered(NewlineToken, sentence)
    newline_token_indices = {(item.begin, item.end) for item in newline_tokens}

    for base_token in ctakes_tokenize(cas, sentence):
        if (
                (base_token.begin, base_token.end) not in newline_token_indices
                # and base_token.get_covered_text()
                # and not base_token.get_covered_text().isspace()
        ):
            base_tokens.append(base_token.get_covered_text())
            token_map.append((base_token.begin, base_token.end))
        else:
            # since these indices are tracked as well in the RT code
            base_tokens.append("<cr>")
            token_map.append((base_token.begin, base_token.end))
    return " ".join(base_tokens), token_map


def get_casoid_entities(casoid):
    """

    Args:
      paragraph: paragraph text
      casoid: paragraph CASoid

    Returns:
      String of detected dose and attribute mentions in the paragraph,
      organized by column
    """
    # tokenized_paragraph = ctakes_tok(paragraph)
    # to avoid confusion with ctakes_tokenize
    axis_spans = set()
    sig_spans = set()
    for w_d_inds, w_dict in casoid.items():
        for task, indcs_dict in w_dict.items():
            for indcs, sent_dict in indcs_dict.items():
                w_inds, dose_indices = w_d_inds
                sig_indices = indcs
                s1, s2 = sig_indices
                window_start, _ = w_inds
                paragraph_sig = window_start + s1, window_start + s2
                axis_spans.add((*dose_indices, "rt_dose"))
                if task != "rt_dose":
                    sig_spans.add((*paragraph_sig, task))
    return axis_spans, sig_spans


class RTAnnotator(CasAnnotator):
    def __init__(self):
        self.taggers = None
        self.out_models = None
        self.anchor_task = None

    def initialize(self):
        # Required for loading cnlpt models using Huggingface Transformers
        AutoConfig.register("cnlpt", CnlpConfig)
        AutoModel.register(CnlpConfig, CnlpModelForClassification)
        # To replace with logging
        # TODO - replace this with a logger message or something
        # TODO - point this to the write place
        taggers_dict, out_model_dict = model_dicts(
            "/home/ch231037/rt_models/"
        )
        self.taggers = taggers_dict
        self.out_models = out_model_dict
        self.anchor_task = "rt_dose"
        print("INITIALIZATION FINISHED", file=sys.stdout)

    def process(self, cas):
        print("NON-CAS REINSERTION PROCESSING STARTING", file=sys.stdout)
        dose_type = cas.typesystem.get_type(DoseModifier)
        fxno_type = cas.typesystem.get_type(DosageCountModifier)
        total_dose_type = cas.typesystem.get_type(TotalDosageModifier)
        fxfreq_type = cas.typesystem.get_type(FrequencyModifier)
        boost_type = cas.typesystem.get_type(StatusChangeModifier)
        site_type = cas.typesystem.get_type(AnatomicalSiteMention)
        date_type = cas.typesystem.get_type(TimeMention)

        procedure_type = cas.typesystem.get_type(ProcedureMention)

        raw_sents = sorted(cas.select(Sentence), key=lambda s: s.begin)

        def cas_clean_sent(sentence):
            return ctakes_clean(cas, sentence)

        cleaned_sents, sent_maps = map(list, zip(*map(cas_clean_sent, raw_sents)))

        def classify_casoid(casoid_pair):
            paragraph, casoid = casoid_pair
            return paragraph, classify_casoid_annotations(casoid, self.out_models)

        def get_casoid_label(casoid_pair):
            paragraph, casoid = casoid_pair
            return casoid_to_label_tuples(paragraph, casoid)

        paragraphs = cleaned_sents

        paragraphs_2_raw_casoids = generate_paragraph_casoids(
            paragraphs, self.taggers, self.anchor_task
        )

        paragraphs_2_classified_cassoids = [
            *map(classify_casoid, paragraphs_2_raw_casoids)
        ]

        # Will use this to populate relations since this is all the relations after coordination
        casoid_labels = [*map(get_casoid_label, paragraphs_2_classified_cassoids)]

        # one (axis_spans, attr_spans) for each paragraph
        paragraph_ent_spans = [
            get_casoid_entities(casoid)
            for _, casoid in paragraphs_2_classified_cassoids
        ]

        # [(axis_0, attr_0), ... , (axis_n, attr_n)] -> [axis_0, ... , axis_n] , [attr_0 , ... ,attr_n]
        axis_spans, attr_spans = zip(*paragraph_ent_spans)
        paragraph_dose_sets = [{(idx_1, idx_2) for idx_1, idx_2, dose_label in paragraph_axes}
                               for paragraph_axes in axis_spans]

        print("NON-CAS REINSERTION PROCESSING FINISHED", file=sys.stdout)

        total_paragraphs = len(sent_maps)
        current = 1

        for relations, token_map, paragraph_dose_set in zip(casoid_labels, sent_maps, paragraph_dose_sets):
            mention_materials = defaultdict(lambda: defaultdict(lambda: []))
            for relation in relations:
                (
                    first_span,
                    second_span,
                    label_dict,
                ) = relation
                relation_label = label_dict["label"]
                sig_label = relation_label.split("-")[-1].lower()
                if relation_label == "DOSE-DOSE":
                    mention_materials[first_span]["dose"].append(second_span)
                    mention_materials[second_span]["dose"].append(first_span)
                elif first_span in paragraph_dose_set:
                    mention_materials[first_span][sig_label].append(second_span)
                elif second_span in paragraph_dose_set:
                    mention_materials[second_span][sig_label].append(first_span)
                else:
                    AttributeError(f"Neither {first_span} nor {second_span} in:\n\n {paragraph_dose_set}")

            for dose_indices, mention_attributes in mention_materials.items():
                num_procedures = len(max(mention_attributes.values(), key=len))
                dose_begin, dose_end = dose_indices
                cas_dose_begin, _ = token_map[dose_begin]
                _, cas_dose_end = token_map[dose_end]
                procedures = [add_type(cas, procedure_type, cas_dose_begin, cas_dose_end) for _ in
                              range(num_procedures)]
                # TODO - if we end up supporting Python >= 3.10 in the future turn this into a case statement
                # 171 is problem line rn
                for attr_type, inds_list in mention_attributes.items():
                    for idx, inds in enumerate(inds_list):
                        local_begin, local_end = inds
                        cas_sig_begin, _ = token_map[local_begin]
                        _, cas_sig_end = token_map[local_end]
                        if attr_type == "boost":
                            procedure_boost = add_type(cas, boost_type, cas_sig_begin, cas_sig_end)
                            procedures[idx].statusChange = procedure_boost
                        elif attr_type == "dose":
                            procedure_total_dose = add_type(cas, dose_type, cas_sig_begin, cas_sig_end)
                            procedures[idx].totalDose = procedure_total_dose
                        elif attr_type == "fxno":
                            procedure_dosage_count = add_type(cas, fxno_type, cas_sig_begin, cas_sig_end)
                            procedures[idx].dosageCount = procedure_dosage_count
                        elif attr_type == "fxfreq":
                            procedure_frequency = add_type(cas, fxfreq_type, cas_sig_begin, cas_sig_end)
                            procedures[idx].frequency = procedure_frequency
                        elif attr_type == "site":
                            procedure_anatomical_site = add_type(cas, site_type, cas_sig_begin, cas_sig_end)
                            procedures[idx].anatomicalSite = procedure_anatomical_site
                        elif attr_type == "date":
                            procedure_start_time = add_type(cas, date_type, cas_sig_begin, cas_sig_end)
                            procedures[idx].startTime = procedure_start_time
            print(f"{current} OUT OF {total_paragraphs} PROCESSED", file=sys.stdout)
            current += 1
        print("FINISHED", file=sys.stdout)
