import sys
import re

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


def _ctakes_tokenize(cas, sentence):
    return sorted(cas.select_covered(BaseToken, sentence), key=lambda t: t.begin)


def _ctakes_clean(cas, sentence):
    base_tokens = []
    token_map = []
    newline_tokens = cas.select_covered(NewlineToken, sentence)
    newline_token_indices = {(item.begin, item.end) for item in newline_tokens}

    for base_token in _ctakes_tokenize(cas, sentence):
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


def _get_casoid_entities(casoid):
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


def _get_tagged_sentence(procedure_map, sentence):
    sentence_text = re.sub(r"(\r|\n)", " ", sentence.get_covered_text())
    sentence_begin = sentence.begin
    ann_sentences = []
    for sent_procs in procedure_map:
        previous = 0
        tagged_sent_pieces = []
        for indices, tag in sorted(sent_procs.items()):
            raw_begin, raw_end = indices
            begin = raw_begin - sentence_begin
            end = raw_end - sentence_begin
            tagged_sent_pieces.append(sentence_text[previous:begin])
            tagged_sent_pieces.append(f" <{tag}> ")
            tagged_sent_pieces.append(sentence_text[begin:end])
            tagged_sent_pieces.append(f" </{tag}> ")
            previous = end
        tagged_sent_pieces.append(sentence_text[previous:])
        ann_sentences.append("".join(tagged_sent_pieces) + " : " + str(sorted(sent_procs.items())))
    return ann_sentences


def _debug_printing(sent_to_procedures, sentences):
    with open("python_debug_out.txt", "wt") as debug_out:
        for idx, bundle in enumerate(zip(sentences, sent_to_procedures)):
            sentence, procedure_map = bundle
            debug_out.write(f"{idx}")
            for out_str in _get_tagged_sentence(procedure_map, sentence):
                debug_out.write(f"{out_str}\n\n")


def _build_mention_dict(relations, dose_set):
    mention_materials = defaultdict(lambda: defaultdict(lambda: []))
    # Procedures are done only in terms of positive relations
    for relation in filter(lambda s: s[2]["label"] != "None", relations):
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
        elif first_span in dose_set:
            mention_materials[first_span][sig_label].append(second_span)
        elif second_span in dose_set:
            mention_materials[second_span][sig_label].append(first_span)
        else:
            AttributeError(f"Neither {first_span} nor {second_span} in:\n\n {dose_set}")
    return mention_materials


def _insert_mentions(cas, token_map, types, mention_materials):
    (
        dose_type,
        fxno_type,
        total_dose_type,
        fxfreq_type,
        boost_type,
        site_type,
        date_type,
        procedure_type
    ) = types

    sent_debug_procedures = []

    for dose_indices, mention_attributes in sorted(mention_materials.items()):
        num_procedures = len(max(mention_attributes.values(), key=len))
        dose_begin, dose_end = dose_indices
        cas_dose_begin, _ = token_map[dose_begin]
        _, cas_dose_end = token_map[dose_end]
        procedures = [add_type(cas, procedure_type, cas_dose_begin, cas_dose_end) for _ in
                      range(num_procedures)]
        local_debug_procedures = []
        # avoiding a weird consequence of just doing
        # local_debug_procedures = [{}] * num_procedures
        # where if it was followed by local_debug_procedures[0]["dose"] = (0,1)
        # we would get [{ "dose" : ( 0, 1 ) } ... { "dose" : ( 0, 1 ) }]
        for _ in range(num_procedures):
            local_debug_procedures.append({(cas_dose_begin, cas_dose_end): "central-dose"})

        # TODO - if we end up supporting Python >= 3.10 in the future turn this into a case statement
        for attr_type, indices_list in mention_attributes.items():
            for idx, indices in enumerate(sorted(indices_list)):
                # redundant but jic
                central_dose = add_type(cas, dose_type, cas_dose_begin, cas_dose_end)
                procedures[idx].dose = central_dose
                local_begin, local_end = indices
                cas_attr_begin, _ = token_map[local_begin]
                _, cas_attr_end = token_map[local_end]
                if attr_type == "boost":
                    procedure_boost = add_type(cas, boost_type, cas_attr_begin, cas_attr_end)
                    procedures[idx].statusChange = procedure_boost
                    local_debug_procedures[idx][(cas_attr_begin, cas_attr_end)] = "boost"
                elif attr_type == "dose":
                    procedure_total_dose = add_type(cas, total_dose_type, cas_attr_begin, cas_attr_end)
                    procedures[idx].totalDose = procedure_total_dose
                    local_debug_procedures[idx][(cas_attr_begin, cas_attr_end)] = "secondary-dose"
                elif attr_type == "fxno":
                    procedure_dosage_count = add_type(cas, fxno_type, cas_attr_begin, cas_attr_end)
                    procedures[idx].dosageCount = procedure_dosage_count
                    local_debug_procedures[idx][(cas_attr_begin, cas_attr_end)] = "fxno"
                elif attr_type == "fxfreq":
                    procedure_frequency = add_type(cas, fxfreq_type, cas_attr_begin, cas_attr_end)
                    procedures[idx].frequency = procedure_frequency
                    local_debug_procedures[idx][(cas_attr_begin, cas_attr_end)] = "fxfreq"
                elif attr_type == "site":
                    procedure_anatomical_site = add_type(cas, site_type, cas_attr_begin, cas_attr_end)
                    procedures[idx].anatomicalSite = procedure_anatomical_site
                    local_debug_procedures[idx][(cas_attr_begin, cas_attr_end)] = "site"
                elif attr_type == "date":
                    procedure_start_time = add_type(cas, date_type, cas_attr_begin, cas_attr_end)
                    procedures[idx].startTime = procedure_start_time
                    local_debug_procedures[idx][(cas_attr_begin, cas_attr_end)] = "date"
        sent_debug_procedures.extend(local_debug_procedures)
    return sent_debug_procedures


def _rt_processing(cas, out_models, taggers, anchor_task):
    raw_sents = sorted(cas.select(Sentence), key=lambda s: s.begin)

    def cas_clean_sent(sentence):
        return _ctakes_clean(cas, sentence)

    cleaned_sents, sent_maps = map(list, zip(*map(cas_clean_sent, raw_sents)))

    def classify_casoid(casoid_pair):
        paragraph, casoid = casoid_pair
        return paragraph, classify_casoid_annotations(casoid, out_models)

    def get_casoid_label(casoid_pair):
        paragraph, casoid = casoid_pair
        return casoid_to_label_tuples(paragraph, casoid)

    paragraphs = cleaned_sents

    paragraphs_2_raw_casoids = generate_paragraph_casoids(
        paragraphs, taggers, anchor_task
    )

    paragraphs_2_classified_casoids = [
        *map(classify_casoid, paragraphs_2_raw_casoids)
    ]

    casoid_labels = [*map(get_casoid_label, paragraphs_2_classified_casoids)]

    return sent_maps, casoid_labels, paragraphs_2_classified_casoids


def _get_rt_types(cas):
    dose_type = cas.typesystem.get_type(DoseModifier)
    fxno_type = cas.typesystem.get_type(DosageCountModifier)
    total_dose_type = cas.typesystem.get_type(TotalDosageModifier)
    fxfreq_type = cas.typesystem.get_type(FrequencyModifier)
    boost_type = cas.typesystem.get_type(StatusChangeModifier)
    site_type = cas.typesystem.get_type(AnatomicalSiteMention)
    date_type = cas.typesystem.get_type(TimeMention)
    procedure_type = cas.typesystem.get_type(ProcedureMention)

    return (
        dose_type,
        fxno_type,
        total_dose_type,
        fxfreq_type,
        boost_type,
        site_type,
        date_type,
        procedure_type
    )


def _get_paragraph_dose_sets(paragraphs_2_classified_casoids):
    paragraph_ent_spans = [
        _get_casoid_entities(casoid)
        for _, casoid in paragraphs_2_classified_casoids
    ]

    # [(dose_0, attr_0), ... , (dose_n, attr_n)] -> [dose_0, ... , dose_n] , [attr_0 , ... ,attr_n]
    dose_spans, _ = zip(*paragraph_ent_spans)
    paragraph_dose_sets = [{(idx_1, idx_2) for idx_1, idx_2, dose_label in paragraph_doses}
                           for paragraph_doses in dose_spans]
    return paragraph_dose_sets


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
            # "/home/ch231037/rt_models/"
            "/home/etg/RT_Resources/trained_train+dev_tested_on_test/test_pipeline_models"
        )
        self.taggers = taggers_dict
        self.out_models = out_model_dict
        self.anchor_task = "rt_dose"

    def process(self, cas):
        ctakes_rt_types = _get_rt_types(cas)

        (
            sent_maps,
            casoid_labels,
            paragraphs_2_classified_casoids
        ) = _rt_processing(
            cas,
            self.out_models,
            self.taggers,
            self.anchor_task,
        )

        paragraph_dose_sets = _get_paragraph_dose_sets(paragraphs_2_classified_casoids)

        # total_paragraphs = len(sent_maps)
        # current = 1

        # sent_to_debug_procedures = []

        for relations, token_map, paragraph_dose_set in zip(casoid_labels, sent_maps, paragraph_dose_sets):
            mention_materials = _build_mention_dict(relations, paragraph_dose_set)
            # sent_debug_procedures = _insert_mentions(cas, token_map, ctakes_rt_types, mention_materials)
            _ = _insert_mentions(cas, token_map, ctakes_rt_types, mention_materials)
            # print(f"{current} OUT OF {total_paragraphs} TOTAL PARAGRAPHS PROCESSED", file=sys.stderr)
            # current += 1
            # sent_to_debug_procedures.append(sent_debug_procedures)
        # _debug_printing(sent_to_debug_procedures, sorted(cas.select(Sentence), key=lambda s: s.begin))
        print("FINISHED", file=sys.stderr)
